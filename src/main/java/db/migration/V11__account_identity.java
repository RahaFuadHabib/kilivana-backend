package db.migration;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.sql.SQLException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Fail closed rather than guessing ownership of colliding or invalid legacy phones. */
public class V11__account_identity extends BaseJavaMigration {
    @Override
    public Integer getChecksum() { return 1102; }

    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        Map<UUID, String> phones = new HashMap<>();
        Map<String, UUID> owners = new HashMap<>();
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("select id, phone_number from users order by id")) {
            while (rows.next()) {
                UUID id = rows.getObject("id", UUID.class);
                String input = rows.getString("phone_number").trim();
                String canonical;
                try {
                    if (!input.matches("\\+?[0-9 ()-]+")) {
                        throw new SQLException("V11: invalid legacy phone for user " + id + "; correct it before retrying");
                    }
                    var parsed = util.parse(input, "KE");
                    if (parsed.hasExtension() || !util.isValidNumberForRegion(parsed, "KE")) {
                        throw new SQLException("V11: invalid legacy Kenyan phone for user " + id + "; correct it before retrying");
                    }
                    canonical = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
                } catch (NumberParseException exception) {
                    throw new SQLException("V11: invalid legacy phone for user " + id + "; correct it before retrying", exception);
                }
                UUID previous = owners.putIfAbsent(canonical, id);
                if (previous != null) {
                    throw new SQLException("V11: duplicate normalized phone for users " + previous + " and " + id
                            + "; resolve ownership before retrying");
                }
                phones.put(id, canonical);
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("alter table users add column username varchar(30), add column region varchar(200)");
            statement.execute("""
                    update users u set region = nullif(btrim(f.farm_location), '')
                    from farmer_profiles f where f.user_id = u.id
                    """);
        }
        try (var update = connection.prepareStatement("update users set phone_number = ?, username = ? where id = ?")) {
            for (var entry : phones.entrySet()) {
                update.setString(1, entry.getValue());
                // Full unsigned UUID in base 36: at most 25 digits + two-character prefix.
                update.setString(2, "u_" + new BigInteger(entry.getKey().toString().replace("-", ""), 16).toString(36));
                update.setObject(3, entry.getKey());
                update.addBatch();
            }
            update.executeBatch();
        }
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    alter table users
                        alter column username set not null,
                        add constraint uq_users_username unique (username),
                        add constraint uq_users_phone_number unique (phone_number),
                        add constraint chk_users_username check (username ~ '^[a-z][a-z0-9_]{2,29}$'),
                        add constraint chk_users_phone_e164 check (phone_number ~ '^\\+254[0-9]{9}$'),
                        add constraint chk_users_region check (region is null or (region = btrim(region) and region <> ''))
                    """);
        }
    }
}

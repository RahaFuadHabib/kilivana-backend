package com.kilivana;

import com.kilivana.support.IntegrationTestSupport;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class AccountMigrationIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;

    private String schema() {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("10").load().migrate();
        return schema;
    }

    private void insert(JdbcTemplate jdbc, String schema, UUID id, String phone, String role) {
        jdbc.update("insert into " + schema + ".users (id,created_at,updated_at,email,password_hash,full_name,phone_number,role,status,verification_status) "
                        + "values (?,now(),now(),?,'hash','Legacy User',?,?,'ACTIVE','UNVERIFIED')",
                id, id + "@example.com", phone, role);
    }

    private void migrate(String schema) {
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();
    }

    @Test
    void populatedV10BackfillsWithoutInventingRegionsOrChangingCredentials() {
        String schema = schema();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            UUID farmer = UUID.randomUUID();
            UUID buyer = UUID.randomUUID();
            UUID smallest = new UUID(0, 0);
            UUID largest = new UUID(-1, -1);
            insert(jdbc, schema, farmer, "0712 345 678", "FARMER");
            insert(jdbc, schema, buyer, "254722345678", "BUYER");
            insert(jdbc, schema, smallest, "+254733345678", "BUYER");
            insert(jdbc, schema, largest, "+254744345678", "BUYER");
            jdbc.update("insert into " + schema + ".farmer_profiles (id,created_at,updated_at,user_id,farm_name,farm_location) "
                    + "values (?,now(),now(),?,'Green Farm','  Kiambu  ')", UUID.randomUUID(), farmer);
            migrate(schema);
            var row = jdbc.queryForMap("select * from " + schema + ".users where id = ?", farmer);
            assertThat(row.get("username")).isEqualTo("u_" + new java.math.BigInteger(farmer.toString().replace("-", ""), 16).toString(36));
            var generated = jdbc.queryForList("select username from " + schema + ".users", String.class);
            assertThat(generated).doesNotHaveDuplicates();
            for (String username : generated) {
                assertThat(username).matches("[a-z][a-z0-9_]{2,29}").hasSizeBetween(3, 27);
                assertThat(com.kilivana.users.service.AccountIdentity.username(username)).isEqualTo(username);
            }
            assertThat(jdbc.queryForObject("select username from " + schema + ".users where id = ?", String.class, smallest)).isEqualTo("u_0");
            assertThat(jdbc.queryForObject("select username from " + schema + ".users where id = ?", String.class, largest)).hasSize(27);
            assertThat(jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conrelid = ?::regclass and conname = 'chk_users_username'", String.class, schema + ".users"))
                    .contains("^[a-z][a-z0-9_]{2,29}$");
            for (String invalid : new String[]{"a", "123farmer", "farmer-name", "farmer name", "abcdefghijklmnopqrstuvwxyz12345"}) {
                assertThatIllegalArgumentException().isThrownBy(() -> com.kilivana.users.service.AccountIdentity.username(invalid));
                assertThatThrownBy(() -> jdbc.update("update " + schema + ".users set username = ? where id = ?", invalid, farmer))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            }
            assertThat(row.get("region")).isEqualTo("Kiambu");
            assertThat(row.get("phone_number")).isEqualTo("+254712345678");
            assertThat(row.get("password_hash")).isEqualTo("hash");
            assertThat(jdbc.queryForMap("select region,phone_number from " + schema + ".users where id = ?", buyer))
                    .containsEntry("region", null).containsEntry("phone_number", "+254722345678");
            assertThat(jdbc.queryForObject("select farm_location from " + schema + ".farmer_profiles where user_id = ?", String.class, farmer))
                    .isEqualTo("  Kiambu  ");
            assertThatThrownBy(() -> jdbc.update("update " + schema + ".users set username = 'UPPERCASE' where id = ?", farmer))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update " + schema + ".users set phone_number = '+254712345678' where id = ?", buyer))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update " + schema + ".users set username = ? where id = ?", row.get("username"), buyer))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void normalizedPhoneCollisionsAbortAtomically() {
        String schema = schema();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            insert(jdbc, schema, UUID.randomUUID(), "0712345678", "BUYER");
            insert(jdbc, schema, UUID.randomUUID(), "+254712345678", "SUPPLIER");
            assertThatThrownBy(() -> migrate(schema)).hasStackTraceContaining("duplicate normalized phone");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = ? and column_name = 'username'", Integer.class, schema))
                    .isZero();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".users", Integer.class)).isEqualTo(2);
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void invalidLegacyPhoneAbortsWithoutRewritingData() {
        String schema = schema();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            UUID id = UUID.randomUUID();
            insert(jdbc, schema, id, "invalid", "BUYER");
            assertThatThrownBy(() -> migrate(schema)).hasStackTraceContaining("invalid legacy phone");
            assertThat(jdbc.queryForObject("select phone_number from " + schema + ".users where id = ?", String.class, id)).isEqualTo("invalid");
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }
}

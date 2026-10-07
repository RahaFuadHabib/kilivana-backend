package com.kilivana;

import com.kilivana.support.IntegrationTestSupport;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class CategorySeedMigrationIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    private static final List<String> NAMES = List.of("Cereals & Grains", "Pulses & Legumes", "Vegetables",
            "Fruits", "Roots & Tubers", "Nuts & Oilseeds", "Herbs & Spices", "Coffee & Tea");

    private Flyway flyway(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target(target).load();
    }

    private void insert(JdbcTemplate jdbc, String schema, UUID id, String name, String sector, boolean active) {
        jdbc.update("insert into " + schema + ".categories (id,version,created_at,updated_at,name,sector,active) "
                + "values (?,0,now(),now(),?,?,?)", id, name, sector, active);
    }

    @Test void freshDatabaseHasEightActiveCategoriesWithFixedIdentities() {
        String schema = "category_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "12").migrate();
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".categories", Integer.class)).isEqualTo(8);
            for (int i = 0; i < NAMES.size(); i++) {
                var row = jdbc.queryForMap("select * from " + schema + ".categories where id = ?",
                        UUID.fromString("c1200000-0000-4000-8000-00000000000" + (i + 1)));
                assertThat(row).containsEntry("name", NAMES.get(i)).containsEntry("sector", "FARM_PRODUCE")
                        .containsEntry("active", true).containsEntry("version", 0L);
                assertThat(row.get("created_at")).isNotNull();
                assertThat(row.get("updated_at")).isNotNull();
            }
            assertThat(flyway(schema, "12").migrate().migrationsExecuted).isZero();
        } finally { jdbc.execute("drop schema " + schema + " cascade"); }
    }

    @Test void existingEmptyDatabaseReceivesBaseline() {
        String schema = "category_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "11").migrate();
            flyway(schema, "12").migrate();
            assertThat(jdbc.queryForList("select name from " + schema + ".categories where active and sector='FARM_PRODUCE'", String.class))
                    .containsExactlyInAnyOrderElementsOf(NAMES);
        } finally { jdbc.execute("drop schema " + schema + " cascade"); }
    }

    @Test void manualEquivalentRetainsIdentityNameAndProductReferenceAndIsActivated() {
        String schema = "category_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "11").migrate();
            UUID manual = UUID.randomUUID();
            insert(jdbc, schema, manual, "  cereals   & GRAINS  ", "FARM_PRODUCE", false);
            insert(jdbc, schema, UUID.randomUUID(), "Fruits", "AGRI_INPUTS", true);
            UUID owner = UUID.randomUUID();
            UUID product = UUID.randomUUID();
            jdbc.update("insert into " + schema + ".users (id,created_at,updated_at,email,password_hash,full_name,phone_number,username,role,status,verification_status) "
                    + "values (?,now(),now(),'seed@test.com','hash','Farmer','+254712345678','seed_farmer','FARMER','ACTIVE','UNVERIFIED')", owner);
            jdbc.update("insert into " + schema + ".products (id,created_at,updated_at,owner_id,status,title,description,unit_price,available_quantity,category_id,sector,seller_type) "
                    + "values (?,now(),now(),?,'ACTIVE','Harvest','Harvest',10,10,?,'FARM_PRODUCE','FARMER')", product, owner, manual);
            flyway(schema, "12").migrate();
            assertThat(jdbc.queryForMap("select name,active,version from " + schema + ".categories where id=?", manual))
                    .containsEntry("name", "  cereals   & GRAINS  ").containsEntry("active", true).containsEntry("version", 1L);
            assertThat(jdbc.queryForObject("select category_id from " + schema + ".products where id=?", UUID.class, product)).isEqualTo(manual);
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".categories where sector='FARM_PRODUCE'", Integer.class)).isEqualTo(8);
        } finally { jdbc.execute("drop schema " + schema + " cascade"); }
    }

    @Test void ambiguousDuplicatesAbortEntireSeed() {
        String schema = "category_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "11").migrate();
            insert(jdbc, schema, UUID.randomUUID(), "fruits", "FARM_PRODUCE", true);
            insert(jdbc, schema, UUID.randomUUID(), " Fruits ", "FARM_PRODUCE", false);
            assertThatThrownBy(() -> flyway(schema, "12").migrate()).hasStackTraceContaining("ambiguous duplicate FARM_PRODUCE category");
            assertThat(jdbc.queryForObject("select count(*) from " + schema + ".categories", Integer.class)).isEqualTo(2);
        } finally { jdbc.execute("drop schema " + schema + " cascade"); }
    }

    @Test void fixedUuidCollisionAbortsWithoutOverwriting() {
        String schema = "category_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        try {
            flyway(schema, "11").migrate();
            insert(jdbc, schema, UUID.fromString("c1200000-0000-4000-8000-000000000004"), "Other", "AGRI_INPUTS", true);
            assertThatThrownBy(() -> flyway(schema, "12").migrate()).hasStackTraceContaining("duplicate key");
            assertThat(jdbc.queryForList("select name from " + schema + ".categories", String.class)).containsExactly("Other");
        } finally { jdbc.execute("drop schema " + schema + " cascade"); }
    }
}

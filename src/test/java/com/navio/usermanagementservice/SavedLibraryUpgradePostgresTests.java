package com.navio.usermanagementservice;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Upgrade a real V9 database containing favorites, independently of JPA's fresh-schema tests. */
@EnabledIfEnvironmentVariable(named="NAVIO_TEST_DB_URL",matches=".+")
class SavedLibraryUpgradePostgresTests {
    @Test void v9FavoritesSurviveUpgradeAndAreCanonicalSearchableBookmarks() throws Exception {
        String base=System.getenv("NAVIO_TEST_DB_URL");String user=System.getenv().getOrDefault("NAVIO_TEST_DB_USERNAME","tripplanner");
        String password=System.getenv().getOrDefault("NAVIO_TEST_DB_PASSWORD","tripplanner");
        String database="library_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String url=base.substring(0,base.lastIndexOf('/')+1)+database;
        try(var admin=DriverManager.getConnection(base,user,password)) {
            admin.createStatement().execute("CREATE DATABASE "+database);
            try {
                Flyway.configure().dataSource(url,user,password).schemas("iam").defaultSchema("iam").target("9").load().migrate();
                var owner=UUID.randomUUID();var home=UUID.randomUUID();
                try(var c=DriverManager.getConnection(url,user,password)) {
                    var insert=c.prepareStatement("INSERT INTO iam.users(id,auth_subject,email,display_name) VALUES(?,?,?,?)");
                    insert.setObject(1,owner);insert.setString(2,owner.toString());insert.setString(3,owner+"@example.com");insert.setString(4,"Upgrade");insert.executeUpdate();
                    var favorite=c.prepareStatement("INSERT INTO iam.user_saved_places(id,user_id,label,name,address,lat,lng,provider_place_id,is_default) VALUES(?,?,?,?,?,?,?,?,?)");
                    favorite.setObject(1,home);favorite.setObject(2,owner);favorite.setString(3,"Café");favorite.setString(4,"Café");favorite.setString(5,"Long road in a distant city");favorite.setDouble(6,1);favorite.setDouble(7,2);favorite.setString(8,"GOOGLE:ChIJcafe");favorite.setBoolean(9,true);favorite.executeUpdate();
                }
                Flyway.configure().dataSource(url,user,password).schemas("iam").defaultSchema("iam").load().migrate();
                try(var c=DriverManager.getConnection(url,user,password)) {
                    var rows=c.createStatement().executeQuery("SELECT identity,search_text FROM iam.saved_library_items");rows.next();
                    assertThat(rows.getString(1)).isEqualTo("google:ChIJcafe");assertThat(rows.getString(2)).contains("cafe");
                    var old=c.createStatement().executeQuery("SELECT is_default,lat,lng FROM iam.user_saved_places");old.next();
                    assertThat(old.getBoolean(1)).isTrue();assertThat(old.getDouble(2)).isEqualTo(1);assertThat(old.getDouble(3)).isEqualTo(2);
                }
            } finally {admin.createStatement().execute("DROP DATABASE "+database+" WITH (FORCE)");}
        }
    }

    /** Production shares one database; community-service V1 created pg_trgm under its social search_path. */
    @Test void migratesWhenAnotherServiceAlreadyInstalledTrigramsOutsidePublic() throws Exception {
        String base=System.getenv("NAVIO_TEST_DB_URL");String user=System.getenv().getOrDefault("NAVIO_TEST_DB_USERNAME","tripplanner");
        String password=System.getenv().getOrDefault("NAVIO_TEST_DB_PASSWORD","tripplanner");
        String database="library_trgm_"+UUID.randomUUID().toString().replace("-","");
        String url=base.substring(0,base.lastIndexOf('/')+1)+database;
        try(var admin=DriverManager.getConnection(base,user,password)) {
            admin.createStatement().execute("CREATE DATABASE "+database);
            try {
                try(var c=DriverManager.getConnection(url,user,password)) {
                    c.createStatement().execute("CREATE SCHEMA social");
                    c.createStatement().execute("CREATE EXTENSION pg_trgm WITH SCHEMA social");
                }
                Flyway.configure().dataSource(url,user,password).schemas("iam").defaultSchema("iam").load().migrate();
                try(var c=DriverManager.getConnection(url,user,password)) {
                    var schema=c.createStatement().executeQuery("SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='pg_trgm'");
                    schema.next();assertThat(schema.getString(1)).isEqualTo("public");
                    var similarity=c.createStatement().executeQuery("SELECT public.similarity('tokyo cafe','tokyo cafes')>0.18");
                    similarity.next();assertThat(similarity.getBoolean(1)).isTrue();
                }
                // Every later startup runs the same callback; it must stay a no-op.
                Flyway.configure().dataSource(url,user,password).schemas("iam").defaultSchema("iam").load().migrate();
            } finally {admin.createStatement().execute("DROP DATABASE "+database+" WITH (FORCE)");}
        }
    }
}

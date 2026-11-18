package kr.co.dtonic.datacatalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class DataCatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataCatalogApplication.class, args);
    }
}

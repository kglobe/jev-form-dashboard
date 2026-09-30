package jev.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JevDashboardApplication {
    public static void main(String[] args) {
        SpringApplication.run(JevDashboardApplication.class, args);
    }
}

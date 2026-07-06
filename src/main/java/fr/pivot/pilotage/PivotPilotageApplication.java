package fr.pivot.pilotage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée de l'application PIVOT — module Pilotage (roadmap, Gantt, portefeuille de projets). */
@SpringBootApplication(scanBasePackages = "fr.pivot.pilotage")
@ConfigurationPropertiesScan("fr.pivot.pilotage")
public class PivotPilotageApplication {

    /** Démarre l'application Spring Boot. */
    public static void main(String[] args) {
        SpringApplication.run(PivotPilotageApplication.class, args);
    }
}

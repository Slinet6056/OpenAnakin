package me.slinet.openanakin;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "anakin")
public class AnakinProperties {
    private Map<String, Integer> models;

    // getter and setter
    public Map<String, Integer> getModels() {
        return models;
    }

    public void setModels(Map<String, Integer> models) {
        this.models = models;
    }
}
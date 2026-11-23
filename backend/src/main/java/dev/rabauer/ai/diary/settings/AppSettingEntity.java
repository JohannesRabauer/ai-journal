package dev.rabauer.ai.diary.settings;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_setting")
public class AppSettingEntity extends PanacheEntityBase {

    @Id
    @Column(name = "setting_key", length = 100)
    public String key;

    @Column(name = "setting_value", length = 4000, nullable = false)
    public String value;
}

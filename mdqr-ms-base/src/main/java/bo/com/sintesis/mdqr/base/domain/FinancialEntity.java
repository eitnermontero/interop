package bo.com.sintesis.mdqr.base.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "entity")
@Getter
@Setter
public class FinancialEntity {

    @Id
    @Column(name = "code", length = 20, nullable = false)
    private String code;

    @Column(name = "participant", length = 255, nullable = false)
    private String participant;

    @Column(name = "entity_group", length = 10, nullable = false)
    private String entityGroup;
}

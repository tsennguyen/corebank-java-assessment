package com.example.corebanking.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Composite primary key for IdempotencyKey entity: (idemKey, scope). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKeyId implements Serializable {

    @Column(name = "idem_key", length = 128, nullable = false)
    private String idemKey;

    @Column(name = "scope", length = 64, nullable = false)
    private String scope;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyKeyId that)) {
            return false;
        }
        return Objects.equals(idemKey, that.idemKey) && Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(idemKey, scope);
    }
}

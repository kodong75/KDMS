package kdms.cdc;

import java.util.Optional;

import io.debezium.config.Configuration;
import io.debezium.spi.storage.OffsetStore;
import io.debezium.spi.storage.OffsetStoreProvider;

/** 엔진이 offset.storage 이름으로 {@link KdmsOffsetStore} 를 찾게 한다(ServiceLoader, META-INF/services) */
public class KdmsOffsetStoreProvider implements OffsetStoreProvider {

    public static final String NAME = "kdms-jdbc";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public OffsetStore create(Configuration config) {
        return new KdmsOffsetStore();
    }

    @Override
    public Optional<String> getOffsetStoreClassName() {
        return Optional.of(KdmsOffsetStore.class.getName());
    }
}

package dev.rabauer.ai.diary.spi.contract;

import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public abstract class EmbeddingGatewayContract {

    protected abstract EmbeddingGateway create();

    /** Every embedding request returns this vector. */
    protected abstract void arrangeVector(float[] vector);

    protected abstract void arrangeUnreachable();

    @Test
    void embeddingsHaveStableDimensionAndModelId() {
        arrangeVector(new float[]{0.1f, 0.2f, 0.3f});
        EmbeddingGateway g = create();
        assertThat(g.modelId()).isNotBlank();
        float[] a = g.embed("one");
        float[] b = g.embed("two words");
        assertThat(a).isNotEmpty();
        assertThat(b).hasSameSizeAs(a);
        assertThat(g.embedAll(List.of("a", "b", "c"))).hasSize(3);
    }

    @Test
    void unreachableRaisesProviderException() {
        arrangeUnreachable();
        assertThatThrownBy(() -> create().embed("x")).isInstanceOf(ProviderException.class);
    }
}

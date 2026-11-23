package dev.rabauer.ai.diary.spi.fake;

import dev.rabauer.ai.diary.api.common.Language;
import dev.rabauer.ai.diary.spi.AudioStorage;
import dev.rabauer.ai.diary.spi.EmbeddingGateway;
import dev.rabauer.ai.diary.spi.LlmGateway;
import dev.rabauer.ai.diary.spi.ProviderException;
import dev.rabauer.ai.diary.spi.ReviewScheduler;
import dev.rabauer.ai.diary.spi.SpeechToText;
import dev.rabauer.ai.diary.spi.TextToSpeech;
import dev.rabauer.ai.diary.spi.VectorStore;
import dev.rabauer.ai.diary.spi.contract.AudioStorageContract;
import dev.rabauer.ai.diary.spi.contract.EmbeddingGatewayContract;
import dev.rabauer.ai.diary.spi.contract.LlmGatewayContract;
import dev.rabauer.ai.diary.spi.contract.ReviewSchedulerContract;
import dev.rabauer.ai.diary.spi.contract.SpeechToTextContract;
import dev.rabauer.ai.diary.spi.contract.TextToSpeechContract;
import dev.rabauer.ai.diary.spi.contract.VectorStoreContract;
import org.junit.jupiter.api.Nested;

import java.time.Duration;

/** The fakes are real implementations of the contracts, so tests built on them are meaningful. */
class FakeContractsTest {

    @Nested
    class VectorStoreTest extends VectorStoreContract {
        @Override
        protected VectorStore create() {
            return new InMemoryVectorStore();
        }
    }

    @Nested
    class AudioStorageTest extends AudioStorageContract {
        @Override
        protected AudioStorage create() {
            return new InMemoryAudioStorage();
        }
    }

    @Nested
    class SchedulerTest extends ReviewSchedulerContract {
        @Override
        protected ReviewScheduler create() {
            return new FakeReviewScheduler();
        }
    }

    @Nested
    class SttTest extends SpeechToTextContract {
        final FakeSpeechToText fake = new FakeSpeechToText();

        @Override
        protected SpeechToText create() {
            return fake;
        }

        @Override
        protected void arrangeSuccess(String text, Language detected) {
            fake.respondWith(r -> new SpeechToText.Result(text, detected, Duration.ofSeconds(1)));
        }

        @Override
        protected void arrangeUnreachable() {
            fake.respondWith(r -> { throw new ProviderException("fake", ProviderException.Kind.UNREACHABLE, "down"); });
        }

        @Override
        protected void arrangeServerError() {
            fake.respondWith(r -> { throw new ProviderException("fake", ProviderException.Kind.ERROR_RESPONSE, "500"); });
        }
    }

    @Nested
    class TtsTest extends TextToSpeechContract {
        final FakeTextToSpeech fake = new FakeTextToSpeech();

        @Override
        protected TextToSpeech create() {
            return fake;
        }

        @Override
        protected void arrangeAudio(byte[] audio, String contentType) {
            fake.respondWith(r -> new TextToSpeech.Audio(audio, contentType));
        }

        @Override
        protected void arrangeUnreachable() {
            fake.respondWith(r -> { throw new ProviderException("fake", ProviderException.Kind.UNREACHABLE, "down"); });
        }
    }

    @Nested
    class LlmTest extends LlmGatewayContract {
        final FakeLlmGateway fake = new FakeLlmGateway();

        @Override
        protected LlmGateway create() {
            return fake;
        }

        @Override
        protected void arrangeChat(String reply) {
            fake.enqueue(reply);
        }

        @Override
        protected void arrangeJson(String json) {
            fake.enqueue(json);
        }

        @Override
        protected void arrangeUnreachable() {
            fake.enqueueFailure(new ProviderException("fake", ProviderException.Kind.UNREACHABLE, "down"));
        }
    }

    @Nested
    class EmbeddingTest extends EmbeddingGatewayContract {
        boolean down;

        @Override
        protected EmbeddingGateway create() {
            FakeEmbeddingGateway g = new FakeEmbeddingGateway();
            return new EmbeddingGateway() {
                @Override
                public float[] embed(String text) {
                    if (down) {
                        throw new ProviderException("fake", ProviderException.Kind.UNREACHABLE, "down");
                    }
                    return g.embed(text);
                }

                @Override
                public String modelId() {
                    return g.modelId();
                }
            };
        }

        @Override
        protected void arrangeVector(float[] vector) {
            down = false;
        }

        @Override
        protected void arrangeUnreachable() {
            down = true;
        }
    }
}

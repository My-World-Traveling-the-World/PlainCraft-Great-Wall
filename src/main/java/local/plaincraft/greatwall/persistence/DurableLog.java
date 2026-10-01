package local.plaincraft.greatwall.persistence;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Durable append-only log port. Implementations must force before
 * publishing a receipt; nothing here is a rebuildable cache.
 */
public interface DurableLog extends AutoCloseable {
    record Entry(String eventKey, String type, int schema, Map<String, String> fields) {
        public Entry {
            Objects.requireNonNull(eventKey, "eventKey");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(fields, "fields");
            if (schema != 1) throw new IllegalArgumentException("only schema 1 is supported");
            fields = Map.copyOf(fields);
            for (Map.Entry<String, String> field : fields.entrySet()) {
                Objects.requireNonNull(field.getKey(), "field key");
                Objects.requireNonNull(field.getValue(), "field value");
                if (field.getValue().length() > 4096) {
                    throw new IllegalArgumentException("field value exceeds 4096 chars: " + field.getKey());
                }
            }
        }
    }

    record Receipt(long sequence, String eventKey, String payloadHash) {
        public Receipt {
            Objects.requireNonNull(eventKey, "eventKey");
            Objects.requireNonNull(payloadHash, "payloadHash");
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        }
    }

    record Record(long sequence, Entry entry, String payloadHash) {
        public Record {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(payloadHash, "payloadHash");
            if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        }
    }

    CompletionStage<Receipt> append(Entry entry);

    CompletionStage<Optional<Record>> lookup(String type, String eventKey);

    CompletionStage<HistoryTypes.BlobRef> storeBlob(String kind, byte[] bytes);

    CompletionStage<byte[]> loadBlob(HistoryTypes.BlobRef ref);

    CompletionStage<Void> flush();

    List<Record> replay(String type, long afterSequence, int limit);

    @Override void close();
}

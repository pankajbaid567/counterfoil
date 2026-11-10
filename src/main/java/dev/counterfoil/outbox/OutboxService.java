package dev.counterfoil.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Writes events into the outbox. Call it inside the transaction that changes the state it announces (I7). */
@Service
public class OutboxService {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public OutboxService(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID enqueue(String type, String aggregateId, Object data) {
        try {
            return jdbc.sql("INSERT INTO outbox (type, aggregate_id, payload) VALUES (:t, :a, :p) RETURNING event_id")
                    .param("t", type).param("a", aggregateId).param("p", mapper.writeValueAsString(data))
                    .query(UUID.class).single();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

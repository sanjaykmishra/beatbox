package app.beat.author;

import java.util.UUID;

public record Author(UUID id, String name, UUID primaryOutletId) {}

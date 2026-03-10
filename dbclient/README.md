# Pioneer DB Client

Clean-room implementation of Pioneer Pro DJ Link database client.

## Status

### ✅ Implemented (Clean-room)
- TCP connection to DB server port
- Greeting protocol
- Setup request protocol
- Track metadata request protocol

### ⚠️ Verified via beat-link (needs clean-room improvement)
- Full protocol field format (captured from beat-link debug logs)
- Player number selection (1-4)
- Response structure identification

### ❌ Needs Implementation
- Metadata response parsing (binary format)
- Track ID discovery (how to know what track is playing)
- rekordbox ID extraction

## Architecture

```
dbclient/
├── connection/
│   └── DbConnection.java      # TCP socket handling
├── packet/
│   └── PacketBuilder.java     # Protocol packet builders
├── protocol/
│   └── DbProtocol.java       # Session management
├── parser/
│   └── MetadataParser.java   # Response parsing (WIP)
└── Main.java                 # CLI entry point
```

## Build

```bash
mvn compile
mvn package
```

## Run

```bash
java -cp target/dbclient-1.0-SNAPSHOT.jar dbclient.Main [player] [host] [port]
```

Example:
```bash
java -cp target/dbclient-1.0-SNAPSHOT.jar dbclient.Main 3 192.168.100.132 48304
```

## Protocol Notes

### Message Format
Each message consists of:
1. **MESSAGE_START**: `0x11 0x87 0x23 0x49 0xae` (5 bytes)
2. **Type field**: NumberField with message type
3. **Length field**: NumberField with payload length
4. **Arguments**: List of fields

### Known Message Types
- `0xfffffffe`: SETUP_REQ
- `0x0000`: Setup response
- `0x2002`: Track metadata request
- `0x4000`: Menu available (response)

### Field Types
- `0x0f`: 1-byte number
- `0x10`: 2-byte number
- `0x11`: 4-byte number
- `0x14`: Binary data

## Known Issues

1. **Metadata parsing**: Response contains binary data that needs proper parsing
2. **Track discovery**: Need to figure out how to get current track ID without beat-link
3. **Error handling**: Minimal error handling currently

## TODO

- [ ] Implement metadata binary parsing
- [ ] Add track discovery from device status
- [ ] Add proper error handling
- [ ] Add support for multiple players
- [ ] Add unit tests for packet builders

## References

- [beat-link](https://github.com/Deep-Symmetry/beat-link) - Reference implementation (used for protocol capture)
- [Pro DJ Link Protocol](https://djlab.denonder.com/) - Unofficial protocol documentation

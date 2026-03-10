# DJ Link API Overview

## System Architecture

```
XDJ-XZ (192.168.100.132)
    ↓ DJ Link Protocol
beat-link (Java library)
    ↓
DeviceManager (Service Layer)
    ↓
REST APIs + WebUI
```

## All Available APIs

### 1. /api/devices
**Purpose**: Real-time player status
**Fields**:
- `number`: Player number (1-4 real, 4=VirtualCdj)
- `playing`: Boolean
- `beat`: Current beat number
- `bpm`: Display BPM (raw ÷ 100)
- `pitch`: Pitch percentage
- `online`: Count of online players
- `master`: Master deck number

### 2. /api/djlink/track
**Purpose**: Track metadata from rekordbox
**Fields**:
- `number`: Player number
- `title`: Track title
- `artist`: Artist name (text)
- `album`: Album name (text)
- `duration`: Duration in seconds
- `trackType`: REKORDBOX, etc.
- `sourceSlot`: SD_SLOT, USB, etc.

### 3. /api/djlink/beatgrid
**Purpose**: Beat grid analysis
**Fields**:
- `number`: Player number
- `beatGridFound`: Boolean
- `beatCount`: Total beats
- `beats[]`: Array of beats (limited to 16 for debugging)
  - `index`: Beat index
  - `beatWithinBar`: 1-4
  - `bpm`: Display BPM
  - `timeMs`: Time in milliseconds

### 4. /api/djlink/cues
**Purpose**: Cue points (hot cues + memory cues)
**Fields**:
- `number`: Player number
- `cueFound`: Boolean
- `cues[]`: Array of cues
  - `type`: HOT_CUE or MEMORY_CUE
  - `index`: Cue number
  - `timeMs`: Time in milliseconds
  - `position`: Raw position (1/1000 frame)
  - `isLoop`: Boolean

### 5. /api/djlink/waveform
**Purpose**: Waveform preview and detail
**Fields**:
- `number`: Player number
- `previewWaveformFound`: Boolean
- `detailedWaveformFound`: Boolean
- `previewLength`: Number of preview segments
- `detailLength`: Number of detail frames
- `previewSample[]`: First 20 preview samples (amplitude)

### 6. /api/djlink/sections
**Purpose**: Inferred song sections (MVP)
**Fields**:
- `number`: Player number
- `sectionsFound`: Boolean
- `inference`: Method used (beatgrid+waveform, beatgrid+waveform+cues)
- `ruleVersion`: sections-mvp-v2
- `currentSection`: Current playing section
- `sections[]`: All sections
  - `index`: Section index
  - `type`: INTRO/BUILD/DROP/BREAK/OUTRO/UNKNOWN
  - `startBeat`, `endBeat`
  - `startTimeMs`, `endTimeMs`
  - `energy`: 0.0-1.0
  - `energyDelta`: Change from previous section
  - `confidence`: 0.3-0.9
  - `reason`: Explanation

### 7. /api/ai/players
**Purpose**: Unified AI interface (legacy name, kept for compatibility)
**Fields**: See /api/players/state

### 8. /api/players/state (RECOMMENDED)
**Purpose**: Unified state for AI/Rule/Trigger systems
**Top-level fields**:
- `online`: Number of active players
- `master`: Master deck number (string)
- `updatedAt`: Unix timestamp
- `ruleVersion`: sections-mvp-v2
- `players[]`: Array of player objects

**Player fields**:
- `number`: Player number
- `playing`: Boolean
- `beat`: Current beat
- `bpm`: Display BPM
- `pitch`: Pitch percentage
- `master`: Boolean (true if this is master deck)
- `active`: Always true for real players
- `triggerKey`: "player-{number}"
- `canTrigger`: Boolean (equivalent to ready)

**track**:
- `title`, `artist`, `album`, `duration`, `trackType`, `sourceSlot`

**analysis**:
- `beatGridFound`, `beatCount`
- `cueFound`, `cueCount`, `hasHotCues`
- `previewWaveformFound`, `detailedWaveformFound`
- `previewLength`, `detailLength`

**currentSection**:
- `index`, `type`, `startBeat`, `endBeat`
- `startTimeMs`, `endTimeMs`
- `energy`, `energyDelta`, `confidence`, `reason`

**decision**:
- `mode`: IDLE/PARTIAL/READY/INSUFFICIENT
- `ready`: Boolean (can trigger)
- `energyLevel`: LOW/MEDIUM/HIGH
- `sectionType`: Section type
- `hasHotCues`: Boolean

## Data Sources

### Real-time (direct from DJ Link)
- number, playing, beat, bpm, pitch, master

### From rekordbox database (dbserver)
- track metadata (title, artist, album, duration)
- beat grid
- waveform
- cue points
- song structure (NOT AVAILABLE - requires additional analysis files)

### Inferred (calculated)
- sections (based on beatgrid + waveform)
- energy level
- section type
- decision.readiness

## Known Limitations

1. **Native phrase/song structure unavailable**: SD card rekordbox analysis doesn't include PNAV data
2. **Sections are inferred**: Based on fixed 32-beat windows + waveform energy
3. **Section types are heuristic**: Not actual rekordbox phrase markers

## Usage Recommendations

### For AI Decision Making
Use `/api/players/state` - it provides:
- Real-time status (playing, beat, bpm)
- Analysis readiness (ready, mode)
- Energy assessment (energyLevel)
- Section awareness (sectionType)

### For Debugging
Use individual APIs:
- `/api/devices` - player status
- `/api/djlink/track` - track info
- `/api/djlink/beatgrid` - detailed beat data
- `/api/djlink/cues` - cue points
- `/api/djlink/waveform` - waveform data
- `/api/djlink/sections` - all section details

### For Trigger Systems
Use `/api/players/state` with fields:
- `canTrigger` - whether to trigger
- `decision.mode` - IDLE/PARTIAL/READY
- `currentSection.energy` - energy level
- `analysis.hasHotCues` - hot cue availability

# Explain.md — the whole backend, in plain words

## 1. The big idea

Imagine a city with cameras at 14 road junctions. Each camera is like a person standing
at a corner with a notebook. When a car goes past, they scribble:

> *"2:32pm. A white car. The number plate looked like CH01AB1234. And here's a sketch
> of what it looked like."*

Each notebook on its own is not very useful. One person only knows about **one corner**.

This backend is the friend who collects all 14 notebooks, reads them together, and works
out: *"Hold on — the white car at corner 3 at 2:32, and the white car at corner 7 at 2:35,
those are the same car. It drove from here to here."*

Once you can do that, you can answer real questions:
- Where did this specific car go? (**trajectory**)
- Is a stolen car in the city right now? (**alerts**)
- Which roads are jammed? (**analytics**)

That's it. Everything else is detail.

---

## 2. The words we use

| Word | What it really means |
| --- | --- |
| **Observation** | One camera saw one vehicle once. A single notebook line. |
| **Vehicle** | Our belief that several observations are the same real car. |
| **Trajectory** | The path of one vehicle: corner, corner, corner, in order. |
| **Camera edge** | "You can drive from camera 3 to camera 7, it's 2.1 km and normally takes 160 seconds." |
| **Embedding** | The "sketch" of the car. 512 numbers describing how it looks. |
| **Plate skeleton** | The plate with lookalike letters flattened. `O`→`0`, `I`→`1`, `B`→`8`, `S`→`5`, `Z`→`2`, `G`→`6`. So `CHO1AB` and `CH01AB` become the same thing. |

### Why the embedding matters

Number plates get misread all the time — rain, night, a motorbike in the way. Sometimes
the camera can't read the plate **at all**.

So we don't rely only on the plate. Each observation carries 512 numbers that describe the
car's appearance. Two pictures of the *same* car give **similar** numbers. Two different
cars give **unrelated** numbers.

We compare them with **cosine similarity** — a score from -1 to 1. Think of it as "how much
do these two sketches point in the same direction?" Same car ≈ 0.58 in our data. Different
cars ≈ 0.00.

That's how we can still follow a car whose plate nobody could read.

---

## 3. How a trajectory is actually calculated

This is the heart of the project. It happens in three steps.

### Step 1 — Gating: "who could this possibly be?"

A new observation arrives at camera 7 at 2:35pm. We do **not** compare it to every car ever
seen — that would get slower every day.

Instead we ask the road map a question:

> *"Which roads lead INTO camera 7? For each one, which cars were last seen at the other end
> of that road, recently enough that they could have arrived by now?"*

We know each road's realistic travel time (`t_min_s` to `t_max_s`). So if the road from
camera 3 takes between 80 and 400 seconds, we look for cars last seen at camera 3 between
80 and 400 seconds ago. A car seen at camera 3 **10 seconds ago** cannot be here — no road
is that fast — so it's not even considered.

In code (`CandidateGateway`), this is one indexed database lookup per incoming road, using
the index `vehicles (last_camera_id, last_seen_at)`. That index is the single most important
one in the system. Without it, every observation would scan the whole table.

There's also a small **fallback pool**: cars seen anywhere in the last 900 seconds whose
plate skeleton matches exactly. This catches a car that took a road our map doesn't cover.

### Step 2 — Scoring: "how much do I believe it?"

Each candidate gets a score built from **three independent pieces of evidence**:

**Plate** — do the plates agree?
```
exact match ...................... +6.0
skeleton match (lookalike chars) .. +4.0
1 character different ............. +2.0
2 characters different ............ +0.5
more than that .................... VETO — throw this candidate away entirely
plate missing on either side ...... 0.0 (and no veto — see below)
```

A missing plate scores **zero, not negative**. Silence is not evidence against a match. The
camera still sent the event because the appearance sketch was worth having.

The "1 character different" measure is not plain spelling distance. A swap between lookalike
characters (`0`↔`O`) costs only 0.5, because that's a *normal* camera mistake. And a mismatch
on a character the camera itself was unsure about costs less than one it was confident about.

**Travel time** — did it arrive when the road says it should?
```
score = 2.0 × exp( −(ln(actual) − ln(typical))² / (2 × 0.30²) )
```
In words: **arriving exactly on time scores 2.0. Arriving much too early or much too late
scores near 0.** We use logarithms because "twice as slow" feels the same whether the trip
is 1 minute or 10 minutes.

**Appearance** — does it look the same?
```
similarity ≥ 0.30 ......... +5.0   (yes, clearly)
0.22 to 0.30 .............. +2.0   (probably)
0.15 to 0.22 ..............  0.0   (can't tell)
below 0.15 ................ −3.0   (no — this is a DIFFERENT car)
```

Add the three up.

### Step 3 — Assignment: "commit, or start fresh"

```
best score ≥ 4.0            → and beats 2nd place by ≥ 1.0 → LINK to that vehicle
best score ≥ 4.0            → but 2nd place is very close  → NEW vehicle
best score < 4.0                                           → NEW vehicle
```

That second rule is important. If two candidates score almost the same, we **refuse to
guess**. Linking the wrong car corrupts *two* journeys. Creating an unnecessary new car only
splits *one*. So when unsure, we start fresh.

The final score becomes a confidence between 0 and 1:
```
confidence = 1 / (1 + e^(−(score − 4.0) / 2))
```
A bare pass (4.0) reads as 0.5. A strong match (12.0) reads as 0.98.

### A worked example

A car with an unreadable plate arrives at camera 14:
```
plate:       0.0   (no plate to compare)
travel time: 1.5   (arrived roughly when expected)
appearance:  5.0   (looks just like vehicle #558)
─────────────────
total:       6.5   →  above 4.0  →  LINK, confidence 0.79
```
**This is the capability we're proudest of.** The plate contributed *nothing*. Timing and
appearance did all the work. Measured against the simulator's ground truth at a 30%
unreadable-plate rate: **817 out of 817** such links correct.

### Turning it into a map line

`TrajectoryService` fetches the whole journey in **one** database query joining
`trajectory_events` + `observations` + `cameras` + `camera_edges`, and returns GeoJSON where
features alternate:

```
Point (sighting) → LineString (hop) → Point → LineString → Point
```

Alternating means the frontend can draw straight through the list without sorting anything.

Each hop is honest about itself:

| Property | Meaning |
| --- | --- |
| `inferred: true` | **Always true.** We never saw the car on the road between cameras — only at the two ends. The line is a reconstruction, not a tracking trail. |
| `geometry_source` | `road` = real road shape from the map. `straight_line` = we have no road here, so this is just a straight line and you should draw it dashed. **We never invent a road we don't have.** |
| `skipped_cameras` | Cameras that this road passes but that did NOT see the car. Something was missed. |
| `detour_suspected` | It took more than 1.6× the normal time — probably a different route than the line shows. |

---

## 4. How the heatmap is sent

### The rule: never do maths on raw data at question time

Imagine being asked *"how busy was that road?"* and having to re-read every notebook line
ever written. It works on day one and dies by month three.

So we **pre-chew** the data. A background job runs every 60 seconds and fills summary tables:

| Table | One row per | Holds |
| --- | --- | --- |
| `agg_camera_density` | camera, per 5 min | how many cars, how many unique, average dwell |
| `agg_edge_flow` | road, per 15 min | how many crossings, median time, congestion |
| `agg_od_matrix` | zone→zone, per hour | how many cars travelled between areas |

`GET /api/analytics/heatmap` reads **only these summary tables**. It never touches raw
observations. That's why it answers in ~9 milliseconds and stays fast forever.

### What makes a road "congested"

```
congestion_ratio = median actual time ÷ free-flow time

< 1.2 → "free"       1.2–1.6 → "moderate"
1.6–2.2 → "heavy"    > 2.2 → "severe"
```

Two deliberate choices here:

**We use the median, not the average.** One driver who stopped for tea would drag an average
into nonsense. The median ignores him.

**If fewer than 5 cars used that road in that 15 minutes, we write `null` instead of a
number.** Three cars don't make a statistic. A `null` that honestly says *"not enough data"*
is more useful than a confident wrong number, and the API tells you how many roads were
excluded for this reason.

### The shape that goes out

```json
{
  "type": "FeatureCollection",
  "max_weight": 97,
  "edge_count": 73,
  "features": [{
    "type": "Feature",
    "geometry": { "type": "LineString", "coordinates": [[76.77,30.74], ...] },
    "properties": {
      "weight": 97, "normalized": 1.0,
      "median_duration_s": 498, "free_flow_s": 195,
      "congestion_ratio": 2.55, "congestion_band": "severe",
      "sample_count": 17
    }
  }]
}
```

`normalized` (0 to 1) is included so the frontend can colour the **first** line it reads
without scanning the whole list to find the busiest. `max_weight` lets it draw a legend.
`sample_count` lets it grey out roads too thin to trust.

Notice what is **not** there: no colours, no line widths, no icons. The server says *what is
true*; the frontend decides *how it looks*. That way changing the colour scheme never
requires a backend change.

---

## 5. Demo mode vs. real life

The cameras don't exist yet. So we built a **simulator** that pretends to be them.

The important part: **the simulator sends real HTTP requests to the same public endpoint a
real camera would use.** It does not cheat by calling internal Java code. If the simulator
can produce an event the real ingestion path rejects, we find out immediately. (This caught
a real bug on day one.)

```
REAL LIFE:  camera hardware ──HTTP──> POST /api/events ──> everything else
DEMO:       simulator       ──HTTP──> POST /api/events ──> everything else
                                       ↑ identical from here on
```

### Two ways to run the simulator

**Live mode** — cars move in real time. A road that takes 200 seconds means the next event
fires 200 seconds later. Timestamps are "now".

**Backfill mode** — generates 2 hours of history in about 8 seconds, with timestamps in the
**past**. This is how a demo has a full heatmap the moment it starts, instead of waiting two
hours.

We deliberately did **not** add a "speed up time 10×" option. If we sped time up, measured
travel times would stop matching the road map's expected times, and every congestion number
would be wrong. Backfill exists precisely so nobody needs that shortcut.

### Things that only exist in demo mode

| | |
| --- | --- |
| `/api/sim/**` endpoints | Only exist under the `sim` profile. In production the code isn't loaded and these return 404. |
| `sim_ground_truth` table | The simulator secretly records which car was really which, so we can *mark our own homework*. It writes this **straight to the database**, never in the event payload — a system that could read the answer off the wire wouldn't be measuring anything. |
| Adjustable damage | `ocrErrorRate`, `nullPlateRate`, `embeddingNoiseSigma`, `congestionFactor` — we can dial up misread plates or traffic jams on demand. |

### One thing that behaves differently, and why

**The live stream only sends genuinely live events.** Anything whose timestamp is more than
60 seconds old is saved to the database but **never streamed**. Otherwise replaying 2 hours
of history would fire thousands of "NEW CAR!" alerts at a dashboard for things that happened
this morning. During a rebuild the stream is muted entirely.

So: history is always *stored*. It is just never *announced*.

---

## 6. Errors — what goes wrong, and what we do

We split problems into three kinds, and treat each differently.

### Kind 1: "Your message was wrong" — reject it, but keep it

A camera sends something bad. We reply HTTP 400 with a reason, **and store the raw message**
in an `ingest_rejects` table so a broken camera can be diagnosed later.

| Reason | What happened |
| --- | --- |
| `malformed_json` | The message was cut off or corrupted mid-send. |
| `empty_body` | Nothing was sent. |
| `missing_event_id` / `missing_camera_id` / `missing_tracklet_id` | A required field was absent. |
| `missing_timestamp_first_seen` / `missing_timestamp_last_seen` | No time given. |
| `negative_dwell` | The car left before it arrived. Clock problem at the camera. |
| `embedding_dimension_mismatch` | The sketch had the wrong number of values — the camera is running a different model version. |
| `unknown_camera_no_location` | A camera we've never heard of, and it didn't say where it is. |
| `invalid_camera_location` | Latitude/longitude outside the possible range. |
| `invalid_field_format` | A field was the wrong type — text where a number belongs. |

**A design decision worth explaining.** The endpoint takes the request body as **raw text**,
not as a pre-parsed object. Normally a web framework parses JSON for you — but if it fails,
the broken message is *gone*, and that's exactly the message you most want to keep. So we
take the text, parse it ourselves, and whatever fails still reaches the rejects table intact.

Rejects are also written in their own separate transaction, so the record survives even
though the request itself failed.

### Kind 2: "Something unexpected broke" — reply properly, don't leak

`GlobalExceptionHandler` catches these and turns each into a clean JSON error:

| Situation | Reply |
| --- | --- |
| Ingest rejection | 400 with `{status, event_id, reason}` |
| Bad parameter / wrong type | 400 `bad_request` |
| Wrong `Content-Type` | 415 |
| Wrong HTTP method | 405 |
| Unknown URL | 404 |
| Deliberate "not found" (unknown vehicle id) | 404 with a message |
| Anything else | 500 `internal_error` — logged in full, but the reply is generic so we never leak internals |

The "deliberate 404" line is there because of a real bug: our catch-everything handler was
swallowing intentional 404s and turning them into 500s. A vehicle that didn't exist reported
"server error" instead of "not found".

### Kind 3: "A helper failed, but the important work must continue"

This is the subtle one. Some parts of the system are *supporting acts*:

- **Alerting** must never break identity resolution.
- **Streaming** must never break ingestion.
- **A dead browser tab** must never crash the car-processing loop.

So these are wrapped in catch-everything blocks (18 of them across the codebase).

**But here is the lesson we learned the hard way.** A caught error that nobody can see is
worse than one that crashes. We had a broken SQL query in the alert writer. It was caught
politely, logged at a low level, and produced **zero alerts for an entire phase of work** —
and looked exactly like "no alerts happened to match today".

So every one of those catches now:
1. Logs at **ERROR** with the full exception, and
2. Increments a counter visible at `GET /api/health/system`:

```json
"failures": {
  "alert_write_failures": 0,
  "alert_evaluation_failures": 0,
  "stream_push_failures": 0,
  "identity_post_processing_failures": 0,
  "analytics_rollup_failures": 0
}
```

**If a feature seems to be silently doing nothing, look here first.**

### Kind 4: Protecting the front door

Cameras give up after 2 seconds. So ingestion must never be slow or blocked:

- Identity work happens on a **background queue**. Ingestion drops the ID in and replies.
- The queue offer **never waits**. If the queue is full it drops the ID with a warning
  rather than making the camera wait. (`POST /api/identity/rebuild` can recover anything lost.)
- Live stream connections are **capped at 50**; number 51 gets a polite 503 instead of the
  server slowly leaking memory.

---

## 7. The whole journey of one event

```
 1. Camera (or simulator) POSTs JSON to /api/events
      │
 2. Read the body as raw TEXT (so a broken message can still be saved)
      │
 3. Validate ─── fails? ──> save raw body + reason to ingest_rejects ──> 400 reply
      │ passes
 4. Unknown camera? ──> auto-register it as "provisional", carry on
      │
 5. Clean up the plate: uppercase, strip spaces/dashes, drop "IND"
    Make the skeleton: O→0, I→1, B→8, S→5, Z→2, G→6
      │
 6. INSERT into observations
    ON CONFLICT (camera_id, tracklet_id) DO NOTHING
    ← a camera retrying after a timeout costs nothing
      │
 7. Drop the new row's id into a queue (never blocks) ──> reply 200 "received"
      │                                                    ⏱ done in milliseconds
      ▼
 8. ONE background thread picks it up
      │
      ├─ GATE ....... which cars could this be? (indexed lookup per incoming road)
      ├─ SCORE ...... plate + travel time + appearance
      ├─ DECIDE ..... link to an existing car, or start a new one
      ├─ WRITE ...... vehicles + trajectory_events, update the appearance average
      ├─ ALERT ...... blacklist? restricted zone? impossible speed? clone?
      └─ STREAM ..... push to dashboards — only if it happened in the last 60 seconds
      │
 9. Every 60 seconds, separately: roll raw data up into the agg_* summary tables
      │
10. Questions get answered from there:
      /api/vehicles/{id}/trajectory ── one query, four tables joined
      /api/analytics/heatmap ──────── summary tables only
      /api/alerts ──────────────────── the alert feed
```

**Why exactly one background thread?** Resolving a car means *reading* its state and then
*changing* it. Two threads doing that for the same car at once would trip over each other.
And the matching is greedy — it only makes sense in time order. One thread is not a
limitation here; it's the correct design.

---

## 8. What this system can do

| Capability | Why it's not obvious |
| --- | --- |
| **Follow a car across the city** | Not just "which cameras saw plate X" — an actual ordered path along real roads with a confidence on every step. |
| **Follow a car with NO readable plate** | Timing + appearance alone. 817/817 correct at a 30% unreadable rate. |
| **Spot cloned number plates** | Two cars wearing one registration. Detected two ways: physically impossible speed between sightings, and "the plate matches exactly but the car looks completely different". |
| **Say when it's unsure** | Every link carries a confidence. Every hop says it's inferred. Thin data returns `null` instead of a guess. |
| **Alert without spamming** | A wanted car crossing 5 cameras produces **one** alert with `repeat_count: 5`, not five alerts. |
| **Live updates** | Server-Sent Events for sightings and alerts, with the alert already carrying map coordinates so no follow-up call is needed. |
| **Traffic analytics that admit their bias** | The zone-to-zone matrix can only count cars seen at 2+ cameras. Every response ships that fraction. |
| **Move to another city by editing one file** | Cameras live in a JSON file. Swap the file, regenerate the road map, and the map view, analytics and simulator all move. No SQL to edit. |
| **Mark its own homework** | Against simulator ground truth, free-flow traffic: precision 1.000, recall 1.000, fragmentation 1.00. See the table below for how that moves under congestion. |

---

## 9. The hard parts (and what we learned)

**The camera placement problem — the worst one.**
Our first road map had **18 connections and 8 completely isolated cameras**. It should have
had 73 and zero. The cause was maddening: the routing service always snaps your point onto
the nearest road, and the route it returns *starts at the snapped point*. Put a camera in the
middle of a junction and the first tiny segment of the route points in some arbitrary
direction — so our "is this camera facing the right way?" check was comparing against noise.

The fix: place each camera **110 metres back along its approach road** and **9 metres onto
the left-hand lane**. Suddenly every camera snaps onto the actual road, headings match within
6°, and we got 73 connections and zero isolated cameras.

**Thresholds that were quietly wrong.**
Appearance thresholds were set at 0.85/0.70/0.50 — sensible values for a real vehicle-
recognition model. But our simulator's numbers sat around 0.44. So **every** comparison fell
into the "different car!" penalty band. Plate matching still carried precision to a perfect
1.000, so nothing looked broken — but recovery of unreadable plates was **exactly zero**.
Measuring the actual distributions and moving the thresholds into the real gap
(0.30/0.22/0.15) took recall from 0.58 to 1.000. *A number that looks perfect can be hiding a
feature that does nothing.*

**Congestion quietly costs accuracy — and we only found out by measuring.**
Gating only considers a car if it arrived inside the road's expected time window
(`t_min_s` to `t_max_s`), and those windows come from *free-flow* driving times. When traffic
is heavy, some journeys take longer than the window allows, so the correct car is never even
a candidate. Measured on identical two-hour runs:

| Traffic | Unreadable plates | Precision | Recall | Fragmentation | Null-plate recall |
| --- | --- | --- | --- | --- | --- |
| Free flow | 5% | 1.000 | 1.000 | 1.00 | 1.000 (128/128) |
| Free flow | 30% | 1.000 | 1.000 | 1.00 | 1.000 (817/817) |
| Congested 2.6× | 5% | 1.000 | 0.995 | 1.02 | 0.954 (124/130) |
| Congested 2.6× | 30% | 1.000 | 0.966 | 1.12 | 0.929 (756/814) |

3% of real journeys overshoot their road's `t_max_s` under that congestion, and every one of
those becomes a missed link. Notice **precision stays at 1.000 throughout** — the system never
starts guessing wrong, it just quietly links less. The honest fix would be to widen the gating
windows using live congestion data, which the analytics already computes. We have not done
that yet.

*How we nearly published the wrong number:* the simulator remembers its settings between
calls, so a congestion factor left over from an earlier experiment was still active when we
went to quote the headline figures. The evaluation read 0.901 and we almost wrote that down as
"the score". Always check what state the thing you are measuring is actually in.

**A delete that ate everything.**
Clearing resolved vehicles with `TRUNCATE ... CASCADE` seemed obvious. But `observations` had
a foreign key pointing at `vehicles`, so the cascade reached through and **deleted every
ingested event** — wiping real data to rebuild an index over it. We replaced it with ordered
deletes and later removed the offending column entirely.

**A race nobody would notice.**
The rebuild pauses the live queue and then deletes two tables. But an item *already being
processed* kept writing, and slipped a row in between the two deletes — foreign key error.
Pausing wasn't enough; we needed a proper lock that waits for in-flight work to finish.

**Results that moved when nothing changed.**
Two identical analytics queries returned different answers. Two separate causes wearing one
disguise: (1) `ORDER BY weight DESC` with tied values returns rows in random order — the data
was identical, only shuffled; (2) a range ending at 05:00 was pulling in the 05:00–06:00
bucket, which was *still filling up*. Fixed with sort tiebreakers and half-open ranges
(`bucket_start < to`).

**The invisible failure.** Already described above — the alert writer's SQL was broken for an
entire phase and looked like a quiet day. This is why every defensive catch now has a
counter.

### The thread running through all of these

Most of these bugs shared one shape: **the system looked fine while quietly doing nothing.**
No crash, no red text — just a feature silently not working.

That's why the design keeps insisting on honesty: `inferred: true` on every hop, `null`
instead of a statistic from three cars, coverage fractions on the matrix, `geometry_source`
saying when a line is fake, and failure counters on the health endpoint.

A system that admits what it doesn't know is worth far more than one that always sounds
certain.

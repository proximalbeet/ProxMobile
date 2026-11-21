# APP-CHAT-LAYOUT: chats laid out like a Discord server (design, no app code)

Nathan, 11-18: "The groups and conversations should look more like a discord server in a way."
His start note: design first, build on ProxMobile's chat channels (C4), the ProxAI DM and notices as they work today.
Same data and permissions as today, no new server-side reach.

Mock-ups: this file (text, phone width ≈ 40 characters = 360 dp) and `design/app-chat-layout.html` (the same screens
drawn at 360 px; open it in any browser, the phone's too). **His taps** are at the end: six choices, each with a
recommendation.

## 1. What's there today (read from `app/`, 0.9.19)

| Piece | Where | What it does |
|---|---|---|
| **The DM** "ProxAI" | `data/Channels.kt` (`Channel.DM`), `chat.json` | The main chat. Its session and summary live in Settings. Briefs and PC notices land here. The power-button / Hey Prox overlay and notification Reply always talk here (`AssistActivity` switches to the DM). |
| **Channels** (C4) | `channels.json`, `chats/<id>.json` | Each is its own conversation: its own Hermes session (its own memory on the PC) and its own model ("Same as Settings", a cloud model, or Local). Flat list, oldest first. |
| **The Chats sheet** | `ui/ChannelsSheet.kt` | A bottom sheet from the `#` button in the chat header. It lists the DM first, then the channels: name, model and "answering…". It has ⋮ (Rename / model, Delete), **New channel** and **Trash**. |
| **New chat / Trash** (C7) | `TrashStore` | "New chat" moves the conversation on screen to the Trash with its session, and the channel starts fresh. Deleting a channel trashes it whole. Restore brings either one back, with its memory. |
| **Notices** (C1/C2) | `NoticeSync` in `data/PcStats.kt`, `ChatRepository.addNotice` | PC notices (brief, scout, pipeline, roadmap, schedule, and quiet ones) become `role:"notice"` messages **in the DM only**, once per key. Notices since your last DM message go along with it to the model (`ChatNotices.note`; a `no_quote` one sends only its kind and title). |
| **Tier "thread"** (#51) | `PcNotice.chatOnly` | A notice with `"tier":"thread"` goes to the DM only: no notification, nothing on the lock screen. |
| **Busy** | `ChatRepository.busyChannel` | Only one answer at a time. The header says "answering in #x…" when it's in another channel. |
| **Unread** | none | Nothing tracks what you've seen. |
| **Groups** | none | There are no groups. **Groups would be new.** |

## 2. The idea in one picture

Discord's phone app maps one-to-one onto what's already here. The layout only needs a few phone-side fields.

| Discord | ProxMobile |
|---|---|
| Direct-messages button at the top of the rail | **The ProxAI DM** (Prox's logo), always on top |
| Servers in the left rail | **Groups**: new, phone-only folders for channels (e.g. "One Touch", "School", "Builds") |
| Text channels inside a server | **Channels**, exactly as today (own session, own model) |
| Threads inside a channel | Not built now (see tap T3). A channel keeps its one live conversation plus the Trash. |
| Unread dot / mention badge | **Unread dot** per channel and group; a **number** only for notices that buzzed |
| Long-press a channel or server | **Long-press menus**: today's ⋮ items plus Move to group, Mark read, Mute dot |
| Swipe right from a chat to see the server list | **Swipe right** (or tap ☰) from the chat to open the rail and channel list |

## 3. Phone mock-ups (recommended shape: A, the drawer)

### 3.1 The chat, full screen (what opens the app, as today)

Only the header changes: `#` becomes ☰, with a dot when anything else is unread. The title shows the group.

```
┌──────────────────────────────────────┐
│ ☰•  One Touch › #quotes        ⤡  ✎ │  ☰ = rail (• = unread elsewhere)
│     ready · cloud (personal mode)    │  ⤡ Compact  ✎ New chat (as today)
│     $0.004 so far · ~12k             │
├──────────────────────────────────────┤
│              ── Today ──             │
│  ┌──────────────────────────┐        │
│  │ Draft the follow-up text │  you   │
│  └──────────────────────────┘        │
│ ┌────────────────────────────┐       │
│ │ Here's a short one: …      │ Prox  │
│ └────────────────────────────┘       │
│                                      │
├──────────────────────────────────────┤
│ 📎 ( Message #quotes            ) 🎤 │
├──────────────────────────────────────┤
│  Chat      Apps      Docs  Settings  │  bottom bar: unchanged
└──────────────────────────────────────┘
```

### 3.2 Swipe right or tap ☰: the rail and the channel list

The rail is 64 dp wide, with round icons (a group's emoji or its first two letters). The panel is the selected
group's channels. Tapping a channel closes the drawer onto it. The chat peeks at the right edge, as in Discord.

```
┌────┬────────────────────────────┬────┐
│(P)•│  ProxAI                    │ ░░ │  (P) = the DM, always first
│────│  Your DM · briefs, notices │ ░░ │
│ OT•│ ───────────────────────────│ ░░ │  OT = One Touch (selected group)
│ ▌  │  ONE TOUCH            ⋯    │ ░░ │
│ SC │  # quotes           ● 2    │ ░░ │  ● = unread  2 = from notices
│    │  # follow-ups       ●      │ ░░ │
│ BU │  # site                    │ ░░ │
│    │  # pipeline   answering…   │ ░░ │  busy shows where it is
│────│                            │ ░░ │
│ +  │  + New channel             │ ░░ │
│    │                            │ ░░ │
│ 🗑 │                            │ ░░ │  Trash moves to the rail foot
└────┴────────────────────────────┴────┘
```

### 3.3 The DM selected in the rail

The DM has no channels, so its panel shows the DM itself with a short "what's new" list. It stays one conversation,
exactly as today. Notices keep landing there.

```
┌────┬────────────────────────────┬────┐
│(P)▌│  ProxAI                    │ ░░ │
│────│  Your DM                   │ ░░ │
│ OT•│ ───────────────────────────│ ░░ │
│ SC │  💬 Open the DM        ● 3 │ ░░ │
│ BU │                            │ ░░ │
│    │  SINCE YOU LOOKED          │ ░░ │
│    │  RHYTHM  Morning brief     │ ░░ │  newest notices, titles only
│    │  SCOUT  3 roofers to call  │ ░░ │  (never the body: lock-safe)
│    │  PC  Build finished   quiet│ ░░ │  quiet = tier "thread"
│    │                            │ ░░ │
│ +  │                            │ ░░ │
│ 🗑 │                            │ ░░ │
└────┴────────────────────────────┴────┘
```

### 3.4 Long-press a channel

```
┌──────────────────────────────────────┐
│  #quotes · One Touch · Haiku 5.5     │
│ ──────────────────────────────────── │
│  ✓  Mark as read                     │
│  ✎  Rename / model                   │  (today's dialog)
│  ⇄  Move to group…                   │
│  🔕 Mute its dot                     │
│  ✚  New chat here (this one → Trash) │
│  🗑  Delete channel                  │  (today's confirm, → Trash)
└──────────────────────────────────────┘
```

Long-press **a group** in the rail: Rename / icon, Move up / down, Mark all read, Delete group. Deleting a group
moves its channels to "Channels" and never deletes a chat. Long-press **the DM**: Mark as read, New chat (→ Trash).
The DM can't be renamed, moved or deleted, as today.

### 3.5 Shape B (the alternative): the list is the Chat tab's home

The Chat tab opens on the rail and list, and tapping a channel pushes the chat full screen. Back returns to the list.

```
┌────┬─────────────────────────────────┐
│(P)•│  ONE TOUCH                 ⋯    │
│────│  # quotes                 ● 2   │
│ OT▌│  # follow-ups             ●     │
│ SC │  # site                         │
│ BU │  # pipeline        answering…   │
│ +  │  + New channel                  │
│ 🗑 │                                 │
├────┴─────────────────────────────────┤
│  Chat      Apps      Docs  Settings  │
└──────────────────────────────────────┘
```

### 3.6 Wide screens (a tablet, the phone sideways): optional

At 600 dp and up, the rail, the list and the chat sit side by side (the "thread on the right"). It's the same
pieces with no drawer. It isn't needed for his phone held upright, so it waits for tap T6.

## 4. What moves, what stays

**Moves**
- The Chats bottom sheet (`ChannelsSheet`) becomes the drawer: rail plus panel. Its rows, dialogs and Trash sheet
  are reused as they are.
- The header's `#` button becomes ☰, with a dot.
- The ⋮ on each row becomes a long-press. ⋮ stays visible too, so the menu is still easy to find; it's dropped
  only if tap T4 says so.
- Trash moves from a button in the sheet to the foot of the rail.

**Stays exactly as it is**
- Every channel's Hermes session, model, summary and files (`channels.json`, `chats/*.json`, `chat.json`).
- The DM rules: notices land only there, the overlay and notification Reply talk there, and notices go along with
  your next DM message.
- New chat, Trash and Restore (C7), Compact, approvals, queued messages, one answer at a time.
- The bottom bar (Chat / Apps / Docs / Settings), the overlay, lock and app-lock rules, and notification rules.
- Nothing new goes to the PC. Groups, order, unread and mute live in the phone's own files. No new endpoint, header
  or permission.

**New, phone-only data (for the build after his pick)**
- `groups.json`: `[{id, name, icon, order}]`. The icon is an emoji or empty (empty means initials).
- In `channels.json`, each channel gets `group` (null means "Channels"), `order` and `muted`. Old files read as
  group null, so every existing channel shows up under "Channels" with nothing lost.
- Each channel and the DM gets a `lastRead` time, set when that chat is on screen. Unread means a message newer
  than `lastRead` that isn't yours: a reply that finished while you were elsewhere, or a notice.
- Trash items keep the channel's group, so a restored channel goes back where it was.

## 5. Notices and tier "thread"

Notices keep their one home, **the DM**. The layout only changes how they *show*:

| Notice | Today | In the new layout |
|---|---|---|
| Buzzes (Settings switch on, not `thread`) | DM message + notification | Same, and the DM icon gets a **number** (how many since you looked) |
| Quiet (switch off) | DM message, no notification | Same, and the DM gets a **plain dot**, no number |
| `tier:"thread"` (#51) | DM only: no notification, nothing on the lock screen | Same, and a **plain dot**. It's listed "quiet" in the DM's "since you looked" list. Never a number or a notification. **No new place shows it while the phone is locked**: the drawer is inside the app, behind the app lock. |
| Prox Hub's (`hub`) | to the Prox Hub app when installed | Unchanged |
| Desk / away word | never shown | Unchanged |

The "since you looked" list in 3.3 shows **kind and title only**, never the body, so a `no_quote` notice shows
nothing it doesn't already show today. Tapping a row opens the DM at that notice.

Routing notices into channels (e.g. scout notices into `#scout`) is **not** proposed. It would change what reaches
the model: notices are quoted into the DM's session only. It's tap T5 if he wants it later; that would be its own
task with its own security read.

## 6. How it fits One Touch Agents

This is Nathan's own tool, the factory side, not a company's system. A group such as "One Touch" or "CL1 Roofing"
is only a label on Nathan's phone over **his own** Hermes sessions. A group never reaches a client's deployment,
data or keys, and nothing about it is shared. If he later wants a per-client group to show that client's own system
(its leads, its calls), that's new server-side reach, and it belongs in the client's own system with its own
permissions, not here.

## 7. His taps

**T1. The shape on the phone**
- **A. Drawer (recommended).** The chat stays what opens. Swipe right or tap ☰ for the rail and channels, as in
  Discord's phone app. The overlay, notification taps and the DM-first habit don't change.
- B. List first. The Chat tab opens on the rail and list, and you tap into a chat. It's one more tap to get back to
  the DM every time.
- C. Keep the bottom sheet, but draw it as a small rail plus sections. This is the smallest change and the least
  like Discord.

**T2. What a group is**
- **A. Your own folders (recommended).** You make them, name them, give them an emoji, and drag channels in.
  Existing channels start in "Channels".
- B. Fixed groups the app makes (e.g. "Projects", "School", "Builds"), which you can't add to.
- C. No groups, only the rail's DM button plus one channel list (Discord look, today's flat list).

**T3. Threads inside a channel**
- **A. None now (recommended).** One live conversation per channel, as today. "New chat" still sends the old one
  to the Trash.
- B. Show a channel's old conversations (from New chat) under it as "threads", with Restore one tap away. This
  needs the Trash to become per-channel history and is its own task.

**T4. Long-press and ⋮**
- **A. Both (recommended).** Long-press opens the menu, and ⋮ stays visible for anyone who doesn't know to hold.
- B. Long-press only, for a cleaner list like Discord.

**T5. Notices**
- **A. DM only, with a number for ones that buzzed and a plain dot for quiet and `thread` ones (recommended).**
- B. Also a "Notices" row under the DM that filters the DM to notices only. It's display only, and the data stays
  the same.
- C. Route kinds into channels (`#scout`, `#pipeline`). This changes what the model reads; it would be its own task
  with a security read.

**T6. Wide screens**
- **A. Later (recommended).** Build the phone drawer now. Side by side on a tablet or the phone sideways comes later.
- B. Now: at 600 dp or wider the rail, list and chat sit side by side.

**Smallest build once he picks (all recommendations):**
- the drawer with the rail and panel
- `groups.json` and the channel `group`, `order` and `muted` fields
- `lastRead` per chat, dots and numbers
- the long-press menus
- the "since you looked" list for the DM

All of it is phone-only, reuses today's dialogs and Trash, and is unit-tested where it's logic (unread rule, old-file
migration, group delete). Roughly one build task.

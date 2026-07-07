# Create: Schematic Compute

[![GitHub](https://img.shields.io/badge/GitHub-y15173334444/create--schematic--compute-blue?style=flat-square&logo=github)](https://github.com/y15173334444/create-schematic-compute)
[![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)](LICENSE)
[![Version](https://img.shields.io/badge/Version-1.2.3-blue?style=flat-square)](https://github.com/y15173334444/create-schematic-compute/releases)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.233-orange?style=flat-square)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10-brightgreen?style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/create)
[![Modrinth](https://img.shields.io/badge/Modrinth-create--schematic--compute-00AF5C?style=flat-square&logo=modrinth)](https://modrinth.com/mod/create-schematic-compute)
[![MC](https://img.shields.io/badge/Minecraft-1.21.1-8B4513?style=flat-square)](https://www.minecraft.net/)

---

<p align="center">
  <b>🎮 Seven Programmable Blocks with a Visual Node-Based Programming System</b><br>
  <i>Drag, connect, and build logic — just like Unreal Engine Blueprints or Blender Geometry Nodes!</i><br>
  <i>Created by <b>StarryNight_Luo</b> (y15173334444)</i>
</p>

---

## 🇬🇧 English

### What is Create: Schematic Compute?

**Create: Schematic Compute** is a **Create mod addon** that introduces **seven programmable blocks** with a **visual node-based programming system**. Instead of writing complex redstone circuits or struggling with command blocks, you simply drag and connect nodes to build logic — just like in Unreal Engine's Blueprint system or Blender's Geometry Nodes.

Each computer has its own internal node graph that runs at **20Hz (every game tick)**, making it suitable for real-time control applications.

---

### Blocks

#### 🖥️ Holographic Monitor
A **3D floating display block** that renders node graph output as a virtual screen in the world.

- **Display Nodes** — TEXT, DATA, IMAGE, IMAGE_SEQUENCE for visual output
- **16×16 Pixel Editor** — Built-in pixel art editor with multi-frame animation support and undo/redo
- **Photoshop-style Layer Panel** — Drag-and-drop layer reordering with 24×24 component thumbnails (text preview, data value, image pixels, sequence frame badge)
- **3D Positioning** — Freely position and rotate the floating screen in world space (X/Y/Z + Roll/Pitch/Yaw)
- **Signal-Driven Movement** — Drive IMAGE/IMAGE_SEQUENCE position (X/Y) and rotation via input signals with per-axis move scale, rotation scale, and invert toggles
- **Redstone Input** — Read signals from Create's Redstone Link network (shared frequency)
- **Real-time Preview** — GUI display mode with WYSIWYG editing of layout, scale, and rotation
- **Undo/Redo** — Ctrl+Z/Y across graph editor and pixel editor
- **Custom Model** — Full Blockbench custom model support

#### 🖥️ Blueprint Computer
Control Create's **Redstone Link network** through visual programming.

- **Redstone Input** — Reads signals from Create's Redstone Link network using frequency items
- **Redstone Output** — Writes computed signals back to the Redstone Link network
- **Private Signal Output** — Transmits float values across named channels to other computers
- **Private Signal Input** — Reads float values from named channels
- **Encapsulation Import / Export** — Save and load encapsulation nodes via the toolbar. Export with a custom filename (auto-renames on duplicate), import from a file browser with scrollable list. Exported `.nbt` files are stored in the `create_schematic_compute/exports/` directory.

#### ⚡ Speed Proxy Controller
Directly control the target RPM of Create's **Speed Controller** blocks on adjacent faces.

- **Speed Control** — Sets the RPM of nearby Speed Controllers (-256 ~ 256 RPM)
- **Private Signal Input** — Reads float values from named channels for cross-computer coordination

#### 🔌 Program Computer
A **sequential logic computer** for timing, counting, and pulse control applications.

- **Redstone I/O** — Communicates through Create's Redstone Link network
- **Dedicated sequential nodes**: Delay, Latch, T Flip-Flop (configurable default), Gate (configurable default), Pulse Extender, Loop, Safety Timer, Accumulator, Continuous Integrator

#### 🪑 Control Seat
A **sit-able controller seat** with real-time keyboard, mouse, and gamepad input capture.

- **58 assignable keys** — Bind any key via click-to-bind UI
- **Two input modes**: Joystick (mouse delta) and View Angle (player rotation difference)
- **Gamepad support** — Dual-stick, 15 buttons, analog triggers (LT/RT) via GLFW gamepad API
- **Sable physics compatible** — Entity yaw syncs with sable sublevel rotation
- **Smooth mode transitions** — No view jump when switching between Joystick and View Angle modes
- **Press `~` to dismount**, **`TAB` to switch input mode**, **`ESC` for pause menu**

#### 📐 Attitude Sensor
Reads the orientation of sable physics structures through a node-based graph.

- **ATTITUDE node** — Outputs pitch and roll from the sublevel's logical pose
- **FORWARD node** — Outputs the world-space forward yaw/pitch of the structure
- **WORLD_VIEW node** — Reads the player's absolute view direction when seated
- **POSE_CONVERT node** — Converts pitch/yaw/roll between coordinate conventions

#### 📡 3D Holographic Radar
A **real-time 3D radar scanner** that detects entities and sable structures in a configurable radius, displaying them as colored blips on an XYZ axis display.

- **Entity Scanning** — Detects players, mobs, and sable structures within configurable range (1-128 blocks)
- **Target Assignment** — Automatically assigns detected targets to `TARGET_OUT` nodes in the node graph, supporting multi-target and single-target modes
- **Manual Locking** — Right-click blips in the air to lock specific targets; locked targets persist across scans
- **Auto Lock** — Automatically assigns the closest target(s) based on scan mode
- **Lock Distance** — Configurable minimum distance (0-128m); targets closer than this are excluded from locking
- **Display Settings** — Adjustable XYZ offset (L/R, U/D, F/B), scan range (R), and display scale (S) via in-GUI settings panel
- **Display Styles** — Two visual modes: **Classic** (colored XYZ axis lines) and **Holographic** (white center cube + semi-transparent blue ground plane, resembling a holographic tactical display)
- **Filter Toggles** — Independently show/hide players, mobs, and sable structures
- **Exclude Host** — Option to exclude the radar's own sable structure from scanning
- **Sable Compatible** — Full support for scanning from moving/rotating sable physics structures; radar position and scan box follow structure movement
- **Real-time GUI** — Dedicated settings panel with numeric input fields for all parameters, live preview updates
- **Custom Model** — Full Blockbench model with rotating scanner disc (BakedModel + `RenderType.solid()` for proper lighting)

**Radar-Specific Nodes:**

| Node | Description |
|------|-------------|
| `TARGET_OUT` | Outputs the assigned target's world position (X, Y, Z), entity ID, and distance. Multiple TARGET_OUT nodes can be used for multi-target tracking |

**Settings Panel:**
| Parameter | Range | Description |
|-----------|-------|-------------|
| Scan Range (R) | 1-128 | Detection radius in blocks |
| Display Scale (S) | 1-32 | XYZ axis display size |
| Lock Distance | 0-128 | Minimum distance for target locking |
| X(L/R) | float | Left/right display offset |
| Y(U/D) | float | Up/down display offset |
| Z(F/B) | float | Forward/back display offset |

**Target Assignment Modes:**
- **Multi-target**: Targets are distributed across TARGET_OUT nodes in round-robin order
- **Single-target**: All TARGET_OUT nodes receive the closest target

---

#### 📱 Portable Terminal

A **handheld remote editor** that scans for nearby programmable blocks and opens their native GUI for remote editing.

- **Device Scanning** — Scans overworld and Sable sub-level blocks within configurable range (1–256 blocks). Sable scanning uses server-side `LevelPlot` chunk iteration with rotation-corrected world position calculation.
- **One-Click Edit** — Select any device from the list to instantly open its native GUI (graph editor, settings, pixel editor, radar controls, etc.)
- **Editable Scan Range** — Numeric input field with auto-refresh on value change; range persists within the same game session.
- **All 7 Block Types** — Supports Monitor, Blueprint, ProgramComputer, Radar, ControlSeat, Sensor, and SpeedProxy.
- **Sable Compatible** — Correctly identifies programmable blocks inside Sable physics structures via classloader-safe interface detection.
- **3D Handheld Model** — Custom Blockbench model with adjusted GUI display angles.

---

### BUS Node System (Signal Bus)

The **Signal Bus** is a global named-channel communication system that allows multiple computers to share data — similar to a publish-subscribe message bus.

#### How it works

1. **BUS_OUT nodes** write their input values to a named channel (e.g., `"power_grid"`)
2. **BUS_IN nodes** on any computer (anywhere in the world) read from that same channel name
3. Each channel supports **bands** — named sub-fields that carry individual float values
4. The bus uses `ConcurrentHashMap` for thread-safe cross-computer communication

#### BUS_OUT (Bus Output)

| Feature | Description |
|---------|-------------|
| **Channel Registration** | Registers the channel name and band definitions in the global `BAND_REGISTRY` |
| **Reference Counting** | Each BUS_OUT increments a reference count; channel cleaned up when count reaches 0 |
| **Conflict Detection** | If two BUS_OUT nodes on different computers use the same channel name, the second one is rejected and marked as conflicted |
| **Band Sync** | Band definitions are synced to all clients for editor UI display |

#### BUS_IN (Bus Input)

| Feature | Description |
|---------|-------------|
| **Dynamic Pins** | Pin count matches the channel's band count; no bands = 1 default pin |
| **Band Names** | Each band has a user-defined name (e.g., `"speed"`, `"direction"`, `"status"`) |
| **One-to-Many** | Multiple BUS_IN nodes can read from the same channel simultaneously |

#### Channel Lifecycle

```
BUS_OUT created → register channel + bands → refCount=1
Other BUS_IN nodes connect → read values from channel
BUS_OUT destroyed → refCount decreases → if 0, channel removed
```

**Channel Names:** User-defined strings (case-sensitive). Channels are per-server; not persisted across world reloads.

**Use Cases:**
- 🏭 **Factory-wide control**: One computer publishes commands, multiple computers receive
- 📊 **Data distribution**: Sensor computer publishes readings, display computers show dashboards
- 🔄 **Cross-computer coordination**: Multiple subsystems communicate via shared channels

---

### Node Reference (81 Types)

| Category | Nodes |
|----------|-------|
| **Values** | CONST, Redstone Input, Private Signal Input, Bus Input |
| **Basic Math** | Add, Subtract, Multiply, Divide, Modulo, Power (A^B), Root (B-th Root), Absolute Value, Ceil, Floor |
| **Advanced Math** | **Formula**, Comparison Router (\|A-B\|), **Round (N Decimals)**, Pose Convert (3-in 2-out), Split |
| **Trigonometry** | Sin, Cos, Tan, Arcsin, Arccos, Arctan2, Sinh, Cosh, **Square Root**, **Natural Log**, **Base-10 Log**, **Exponential (e^x)**, **Secant**, **Cosecant**, **Cotangent**, **Angle Unwrap**, Direction (3-in 3-out) |
| **Logic** | Greater Than, Less Than, **Greater Than or Equal**, **Less Than or Equal**, Equals, Bool (Toggle), Gate, **OR Gate** |
| **Control** | PID Controller, Power PID, Clamp, Map Range |
| **Output** | Redstone Output, Private Signal Output, Speed Control, **Bus Output** |
| **Display (Monitor only)** | TEXT, DATA, IMAGE, IMAGE_SEQUENCE |
| **Sequential** | Delay, Latch, T Flip-Flop, Pulse Extender, Loop, Safety Timer, Accumulator, **Continuous Integrator** |
| **Controls (Input Ctrl)** | KEYBOARD (58 keys), Mouse Button (L/R), Mouse Joystick (X/Y), Gamepad Joystick (LX/LY/RX/RY), Gamepad Button (15 buttons), **Gamepad Trigger (LT/RT)** |
| **Sensors (Input Sensor)** | World View (yaw/pitch), Attitude (pitch/roll), Forward (yaw/pitch), View Angle (pitch/yaw), **Acceleration (X/Y/Z)**, **Velocity (X/Y/Z)**, **World Position (X/Y/Z)**, **Target Output (X/Y/Z/entityId/distance)** |
| **Structure** | Encapsulation |
| **Encapsulation I/O** | Input, Output |

#### Detailed Node Table

##### Values
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| CONST | - | float | value | Outputs a constant value |
| REDSTONE_IN | - | signal | frequency item ×2 | Reads from Redstone Link network |
| PRIVATE_IN | - | val | channel | Reads float from named channel |
| BUS_IN | - | band×N | bus name | Reads band values from a shared bus channel |

##### Basic Math
| Node | Inputs | Output | Description |
|------|--------|--------|-------------|
| ADD | A, B | float | A + B |
| SUB | A, B | float | A - B |
| MUL | A, B | float | A × B |
| DIV | A, B | float | A ÷ B (returns 0 if B=0) |
| MOD | A, B | float | A % B |
| POW | A, B | float | A ^ B (A to the power of B) |
| ROOT | A, B | float | B-th root of A (returns 0 if B=0) |
| ABS | in | float | Absolute value of input |
| CEIL | in | int | Round up to nearest integer |
| FLOOR | in | int | Round down to nearest integer |

##### Advanced Math
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| FORMULA | dynamic (per script) | dynamic (per @output) | script | **Multi-line script editor** — supports assignments (`var = expr`), `@output` named outputs, `--` comments, `\` line continuation. Auto-wrap + drag-select. Functions: sin/cos/tan/asin/acos/atan2/sinh/cosh/sqrt/ln/log/exp/sec/csc/cot/abs. [See usage below](#formula-script-node) |
| INTERP (Comparison Router) | A, B | A, B | - | A≥B → A port outputs A-B, else B port outputs \|B-A\| |
| ROUND | in | float | decimals | Round to N decimal places (default 2) |
| POSE_CONVERT | pitch_a, yaw_a, roll | pitch_b, yaw_b | - | Pose conversion (3-in 2-out) |
| SPLIT | in | +out, -out | - | Split positive/negative: positive→+out, negative→\|-out\| |

##### Trigonometry
| Node | Inputs | Output | Description |
|------|--------|--------|-------------|
| SIN | in | float | Sine of input (degrees) |
| COS | in | float | Cosine of input (degrees) |
| TAN | in | float | Tangent of input (degrees) |
| ASIN | in | float | Arcsine in degrees |
| ACOS | in | float | Arccosine in degrees |
| ATAN2 | y, x | float | Arctangent of y/x in degrees |
| SINH | in | float | Hyperbolic sine |
| COSH | in | float | Hyperbolic cosine |
| SQRT | in | float | Square root of input (returns 0 if negative) |
| LN | in | float | Natural logarithm of input (returns 0 if ≤0) |
| LOG | in | float | Base-10 logarithm of input (returns 0 if ≤0) |
| EXP | in | float | e to the power of input |
| SEC | in | float | Secant: 1 / cos(input°) |
| CSC | in | float | Cosecant: 1 / sin(input°) |
| COT | in | float | Cotangent: 1 / tan(input°) |
| ANGLE_UNWRAP | in | float | Unwraps angle to avoid ±180° jumps; stateful (remembers last output) |
| DIRECTION | ax, ay, az, bx, by, bz | yaw, pitch, distance | World-space direction from point A to B with distance |

##### Logic
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| GT | A, B | bool | - | A > B → 1 |
| LT | A, B | bool | - | A < B → 1 |
| GE | A, B | bool | - | A >= B → 1 |
| LE | A, B | bool | - | A <= B → 1 |
| EQ | A, B | bool | - | A = B → 1 |
| OR | A, B | bool | - | A > 0.5 or B > 0.5 → 1 |
| BOOL | in | bool | inverted | in > 0 → 1, ≤ 0 → 0; inverted=1 flips output |
| GATE | val, Open, Close, Tog | out | default | val passes thru when open; Open/Close set state, Tog toggles |

##### Control
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| PID | SP, PV | ctrl | kp, ki, kd, scale, ilimit | Classic PID (setpoint vs process variable), output 0~16, anti-windup |
| PID_POWER | SP, PV, base | power | kp, ki, kd, ilimit | PID with base power input and PV feedback |
| CLAMP | In, Min, Max | float | - | Clamp input between Min and Max |
| MAP | In, InMin, InMax, OutMin, OutMax | float | - | Map input range to output range |

##### Output
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| REDSTONE_OUT | In | - | frequency item ×2 | Writes signal to Redstone Link network (clamped 0~15) |
| PRIVATE_OUT | val | - | channel | Writes float to named channel |
| SPEED_CTRL | speed, dir | rpm | - | Sets Speed Controller RPM; dir>0.5 reverses direction |
| BUS_OUT | band×N | - | bus name | Writes input values to a shared bus channel |

##### Display (Monitor only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| TEXT | - | - | text, color | Displays text content |
| DATA | val | - | color | Displays input float value |
| IMAGE | X, Y, rotation | - | moveScaleX/Y, rotationScale, invertX/Y | 16×16 pixel image, signal-driven position + rotation |
| IMAGE_SEQUENCE | X, Y, frame, rotation | - | moveScaleX/Y, rotationScale, invertX/Y, frames | Multi-frame animation, signal-driven position + rotation + frame select |

##### Sequential (Program Computer only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| DELAY | in | out | ticks | Delays output by N ticks |
| LATCH | S, R | q | default | S≥1 sets, R≥1 resets, holds value; configurable initial state |
| T_FLIPFLOP | in | tog | default | Toggles output on rising edge; configurable initial state |
| PULSE_EXTEND | in | pulse | ticks | Extends input pulse by N ticks |
| LOOP | in | clk | count, interval | Fires pulse every interval tick, repeats count times |
| FUSE | in | pulse | cooldown | Trigger → 2-tick pulse → cooldown N ticks |
| ACCUMULATOR | +, - | val | step | Rising-edge triggered; + adds step, - subtracts step |
| INTEGRATOR | +, -, clear | val | step, interval, limit | Continuous integration every N ticks; both + and - active → hold; clear resets to 0; clamped [0, limit] |

##### Input Ctrl (Control Seat only)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| KEYBOARD | - | 1/0 | key | Bindable keyboard key (58 keys), outputs 1 when pressed |
| MOUSE_JOYSTICK | - | X, Y | - | Mouse delta output -1~1 (joystick mode) |
| MOUSE_BUTTON | - | L, R | - | Left/right mouse button state |
| GAMEPAD_JOYSTICK | - | LX, LY, RX, RY | - | Dual-stick gamepad axes -1~1 |
| GAMEPAD_BUTTON | - | 1/0 | button | Gamepad button (15 buttons), click-to-bind via frame-polling |
| GAMEPAD_TRIGGER | - | LT, RT | - | Gamepad analog triggers (0.0 ~ 1.0) |

##### Sensor (Attitude Sensor / Control Seat)
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| WORLD_VIEW | - | yaw, pitch | - | Player absolute world view direction |
| ATTITUDE | - | pitch, roll | - | Sublevel attitude (pitch/roll) |
| FORWARD | - | yaw, pitch | - | Sublevel forward direction in world space |
| VIEW_ANGLE | - | pitch, yaw | - | Player view angle delta (view angle mode) |
| ACCELERATION | - | X, Y, Z | - | Structure-local acceleration (fwd/back, up/down, left/right), computed at 20Hz from velocity |
| VELOCITY | - | X, Y, Z | - | Structure-local velocity (fwd/back, up/down, left/right), ×2 m/s |
| POSITION | - | X, Y, Z | offsetX, offsetY, offsetZ | World position with configurable offset |
| TARGET_OUT | - | X, Y, Z, entityId, distance | - | Radar target output (Radar only) |

##### Structure
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| ENCAPSULATION | dynamic | dynamic | - | Nest sub-graphs inside a single node; double-click to enter sub-graph editor |

##### Encapsulation I/O
| Node | Inputs | Output | Params | Description |
|------|--------|--------|--------|-------------|
| ENCAP_INPUT | - | float | name | External input pin for encapsulation node |
| ENCAP_OUTPUT | float | - | name | External output pin for encapsulation node |

---

### Featured Algorithm Nodes

| Node | Description |
|------|-------------|
| **PID Controller** | Full PID with SP/PV inputs, configurable kp/ki/kd/scale/ilimit, anti-windup with integral capping |
| **Power PID** | PID with SP/PV/base inputs, configurable kp/ki/kd/ilimit, output combines PID correction with base power |
| **Comparison Router** | A≥B → A port outputs A-B, else B port outputs \|B-A\|. Smart signal routing |
| **Pulse Extender** | Extends input pulse by N ticks |
| **Loop** | Fires pulses every interval ticks, repeat count times |
| **Formula (Script)** | Multi-line script editor — assignments, `@output` named pins, `--` comments, `\` continuation. Word-wrap, drag-select, Ctrl+A, arrow key navigation. Functions: sin/cos/tan/asin/acos/atan2/sinh/cosh/sqrt/ln/log/exp/sec/csc/cot/abs |

---

### Formula Script Node

The FORMULA node is now a **multi-line script editor** (v1.2.0+). It supports:

- **Assignments**: `varName = expression` — intermediate variables reusable in later lines
- **Named outputs**: `@output varName` — declares output pins with custom names
- **Comments**: `-- text` — lines starting with `--` are ignored
- **Line continuation**: end a line with `\` to continue on the next line
- **Default output**: if no `@output` is declared, the last expression line becomes the single output

**Example — Ballistic trajectory:**
```
-- Ballistic calculation
dx = X1 - X0
dz = Z1 - Z0
w = sqrt(dx*dx + dz*dz)
secTheta = 1 / cos(THETA)
y = (99 * secTheta / (20 * N) + tan(THETA)) * w + 99 * ln(1 - 2 * (w * secTheta - K) / (199 * N)) / (20 * ln(100/99)) - 99 * K / (20 * N) + 2
@output y
@output w
@output secTheta
```
This creates **7 input pins** (X1, X0, Z1, Z0, THETA, N, K) and **3 output pins** (Y, W, SEC_THETA).

**Editor shortcuts:** Enter = new line, ↑↓ = navigate lines, Home/End = line start/end, Ctrl+A = select all, drag mouse = select text.

---

### How to Use

1. **Place** one of the seven blocks
2. **Right-click** to open the node editor
3. **Right-click empty space** to open the add-node menu (categorized & collapsible)
4. **Left-click a node** to edit its parameters
5. **Drag from output pins** to **input pins** to connect nodes
6. **Press `X`** to delete a node (hover over it), or **`TAB` + Left-click** a connection to delete it
7. Press **Compile**, then **Run**

**Controls:**
| Action | Input |
|--------|-------|
| Open add-node menu | Right-click on empty space |
| Edit node parameters | Left-click node, then click ▶ |
| Connect nodes | Drag from output pin to input pin |
| Delete node | Press **X** while hovering over it |
| Delete connection | **TAB** + Left-click on connection |
| Delete selected node(s) | Delete / Backspace |
| Box select nodes | TAB + Left-click drag |
| Toggle node selection | TAB + Left-click on node |
| Move selected nodes | TAB + Left-click drag on selected node |
| Duplicate node(s) | Ctrl + D |
| Expand/collapse edit area | Click ▶ / ▼ on node header |
| Color customization | Click **Style** button |
| Toggle grid snap | Click **Grid** button |
| Zoom in/out | Scroll wheel |
| Pan canvas | Right-click drag |
| **Control Seat — Sit down** | Right-click on seat (empty hand) |
| **Control Seat — Open editor** | Shift + Right-click on seat |
| **Control Seat — Dismount** | Press **`~`** |
| **Control Seat — Switch mode** | Press **`TAB`** |
| **Control Seat — Pause / release mouse** | Press **`ESC`** |

---

### Schematic Support
All seven blocks fully support **Create's Schematicannon**. Your node graphs, parameters, and running state are preserved when saving and loading schematics — no data loss.

This means you can:
- 🏗️ **Build complex logic** in creative mode, then **print it in survival** with the Schematicannon
- 📋 **Copy and paste** computer configurations across your world
- 🌍 **Share your creations** as schematic files with other players

> Uses Create's official `IMergeableBE` interface and `SafeNbtWriter` registration for reliable data preservation.

---

### Block Properties

All seven blocks share consistent properties:

| Property | Value |
|----------|-------|
| **Hardness** | 1.0 (breakable by hand, no tool required) |
| **Hand break** | Drops block item **without** NBT (fresh block) |
| **Wrench (right-click)** | Rotates block (cycles FACING direction) |
| **Wrench (shift + right-click)** | Picks up block **with** full NBT preservation (graph, running state, pid values) |



### Sable Physics Integration

This mod has **deep integration** with the **Sable physics engine** for Minecraft. The Control Seat and Attitude Sensor are designed to work on rotating physics structures.

#### How it works

Both blocks implement `BlockEntitySubLevelActor` — Sable's interface for block entities that participate in subworld physics simulation. When a block is placed inside a sable sublevel:

1. **`sable$physicsTick()`** fires every physics tick (concurrent with the server tick)
2. The sublevel's **logical pose** (orientation quaternion) is read via `subLevel.logicalPose()`
3. Euler angles are extracted using JOML's `getEulerAnglesYXZ()` — matching Minecraft's YXZ rotation convention
4. Yaw is converted from JOML's CCW-positive convention to Minecraft's CW-positive convention
5. A **relative rotation** is computed (subtracting the initial sublevel offset) so that at-rest structures read as 0° deviation

#### Thread safety

The sable physics thread and the Minecraft server thread run concurrently. Shared fields (`cachedSubYaw`, `cachedSubPitch`, `cachedSubRoll`, `hasSubPose`, `cachedBlockFacingYaw`, `initialSubYaw`) are all marked **`volatile`** to ensure cross-thread visibility.

#### Control Seat + Sable

| Feature | Description |
|---------|-------------|
| **Entity yaw sync** | The ControlSeatEntity's yaw tracks the sublevel rotation (`blockFacing - relativeYaw`), so the player's camera follows the structure |
| **Joystick mode** | Player view locks to entity yaw, automatically rotating with the physics structure |
| **View angle mode** | Client sends `playerYaw - vehicleYaw` delta; server reconstructs absolute world yaw using entity yaw |
| **Relative rotation tracking** | Initial sublevel yaw recorded on first physics tick; subsequent rotations measured as offsets from initial |

#### Attitude Sensor + Sable

| Node | Source | Convention |
|------|--------|------------|
| **ATTITUDE** | `subLevel.logicalPose().orientation()` → `getEulerAnglesYXZ()` | Output: pitch (X), roll (Z) |
| **FORWARD** | Block facing vector rotated by sublevel quaternion | Output: world-space yaw/pitch |
| **ACCELERATION** | `subLevel.latestLinearVelocity` differentiated at 20Hz | Structure-local X/Y/Z (fwd/back, up/down, left/right) |
| **WORLD_VIEW** | Player absolute yaw (view angle diff + entity yaw) | Updates in view angle mode only |

#### Without Sable

**Sable is optional.** When sable is not installed:

| Component | Behavior |
|-----------|----------|
| Control Seat | Fully functional — keyboard/mouse/gamepad input, key binding, two input modes, ESC menu |
| Attitude Sensor | Graph editor, math/logic/output nodes, redstone I/O work. ATTITUDE/FORWARD/WORLD_VIEW output 0 |

---

### Technical Highlights

| Feature | Description |
|---------|-------------|
| ⚡ **Topological sort evaluation** | Nodes evaluated in dependency order, O(1) input query cache |
| 🚀 **GC-friendly** | `GraphEvaluator` instances reused across ticks, reducing GC pressure |
| 🔄 **Private Signal Bus** | Global named-channel float communication across computers |
| 🎯 **Reflection-based speed control** | Directly sets `SpeedControllerBlockEntity.targetSpeed` field |
| 🧹 **PID anti-windup** | Full SP/PV dual-input PID with configurable kp/ki/kd/scale/ilimit, integral capping |
| 🛡️ **Cycle detection** | Blocks execution if circular dependencies are detected in the graph |
| 🎮 **GLFW raw input** | Control Seat reads keyboard/mouse/gamepad at the GLFW level, not through Minecraft's keybinding system |
| 🔄 **Sable physics integration** | Control Seat and Attitude Sensor implement `BlockEntitySubLevelActor` for sublevel pose reading |

---

### Recipes

| Block | Materials |
|-------|-----------|
| **Blueprint Computer** | 2× Redstone Link, 1× Precision Mechanism, 2× Glass Pane, 1× Repeater, 1× Comparator, 2× Brass Casing |
| **Speed Proxy Controller** | 4× Brass Ingot, 1× Cogwheel, 2× Glass Pane, 1× Comparator, 1× Andesite Casing |
| **Program Computer** | 4× Andesite Casing, 1× Repeater, 2× Glass Pane, 1× Comparator, 1× Andesite Alloy |
| **Control Seat** | 1× Heavy Weighted Pressure Plate, 2× Iron Ingot, 1× Brass Casing, 1× Redstone, 4× Redstone Link |
| **Attitude Sensor** | 6× Iron Ingot, 1× Repeater, 1× Comparator, 2× Brass Casing |
| **Holographic Monitor** | 2× Redstone Link, 1× Precision Mechanism, 2× Glass Pane, 1× Brass Casing, 2× Glowstone Dust |
| **3D Holographic Radar** | 2× Monitor, 4× Iron Ingot, 1× Brass Casing, 2× Redstone Block |

*(Requires JEI to view in-game)*

---

### Dependencies

- **Minecraft**: 1.21.1
- **NeoForge**: 21.1.233+
- **Create**: 6.0.10+

---

### Creative Ideas

| Idea | Description |
|------|-------------|
| 🏭 **Smart factory control** | Use PID nodes for automatic RPM regulation |
| 🔄 **Automated sequences** | Build complex automation with Delay and Loop nodes |
| 🎛️ **Multi-computer coordination** | Link computers via Private Signal Bus |
| 🖥️ **Live factory dashboards** | Use Holographic Monitor to display sensor data and KPIs in real-time |
| 🎯 **Wireless remote control** | Combine with Redstone Link network |
| ⚙️ **Precision speed matching** | Speed Proxy Controller for exact RPM matching |

---

### FAQ

**Q: The node editor feels laggy?**  
A: Check if you have too many nodes or complex PID operations. Keep the number of continuously running PIDs reasonable.

**Q: Speed Proxy Controller not working?**  
A: Make sure the Speed Proxy Controller is placed directly adjacent (one of 6 faces) to a Speed Controller.

**Q: Computer state lost after schematic placement?**  
A: Make sure you're using Create 6.0.10+. This mod registers complete NBT save/load interfaces.

**Q: Can computers communicate with each other?**  
A: Yes! Use Private Signal Input/Output nodes with named channels. Blueprint Computers and Program Computers can exchange float values across distances.

---

### Source Code

📦 **GitHub Repository**: [https://github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)

The project is fully open-source under the **MIT License**. Contributions, issues, and feature requests are welcome!

---

### Installation

1. Install **NeoForge 21.1.233+**
2. Install **Create 6.0.10+**
3. Place the mod `.jar` file into your `mods` folder
4. Launch the game!

---

### License

MIT License © 2026 StarryNight_Luo

---

## 🇨🇳 中文

### 什么是 Create: Schematic Compute？

**Create: Schematic Compute（机械动力：蓝图计算机）** 是一个**机械动力附属模组**，添加了**七种可编程方块**，采用**可视化节点图编程系统**。无需编写代码或搭建复杂的红石电路，只需拖拽连接节点即可构建逻辑——类似虚幻引擎的蓝图系统或 Blender 的几何节点。

每台计算机拥有独立的节点图，以 **20Hz（每游戏刻）** 的频率运行，适合实时控制应用。

---

### 方块

#### 🖥️ 全息显示器
一个**3D 悬浮显示方块**，将节点图输出渲染为世界中的虚拟屏幕。

- **显示节点** — TEXT、DATA、IMAGE、IMAGE_SEQUENCE 用于视觉输出
- **16×16 像素编辑器** — 内置像素画编辑器，支持多帧动画
- **3D 自由定位** — 在世界空间中自由定位和旋转屏幕（X/Y/Z + 滚转/俯仰/偏航）
- **信号驱动移动** — IMAGE/IMAGE_SEQUENCE 通过 X/Y 输入信号驱动位置，移动比例可配置
- **红石输入** — 从红石链接网络读取信号（共享频率）
- **实时预览** — GUI 显示模式，所见即所得的布局/缩放/旋转编辑
- **自定义模型** — 完整 Blockbench 自定义模型支持

#### 🖥️ 蓝图计算机
通过可视化编程控制机械动力的**红石链接网络**。

- **红石输入** — 使用频率物品从机械动力的红石链接网络读取信号
- **红石输出** — 将计算后的信号写回红石链接网络
- **私有信号输出** — 通过命名通道将浮点数传输到其他计算机
- **私有信号输入** — 从命名通道读取浮点数
- **封装节点导入/导出** — 通过工具栏按钮保存和加载封装节点。导出时自定义文件名（同名自动追加序号），导入时从文件列表中选择。导出的 `.nbt` 文件存储在 `create_schematic_compute/exports/` 目录下。

#### ⚡ 转速代理控制器
直接控制相邻 6 个面上机械动力**转速控制器**的目标 RPM。

- **转速控制** — 设置附近转速控制器的目标转速（-256 ~ 256 RPM）
- **私有信号输入** — 从命名通道读取浮点数，实现跨计算机联动

#### 🔌 编程计算机
专为**时序逻辑**设计的计算机，适用于延时、计数和脉冲控制。

- **红石 I/O** — 通过机械动力的红石链接网络通信
- **专用时序节点**：延时、锁存器、T 触发器（可配置默认）、闸门（可配置默认）、脉冲延长、循环、保险、累计器、连续积分器

#### 🪑 控制座椅
一个**可乘坐的控制座椅**，支持实时键盘、鼠标和手柄输入捕获。

- **58 个可绑定按键** — 通过点击绑定 UI 分配按键
- **两种输入模式**：摇杆（鼠标增量）和视角差（玩家旋转差）
- **手柄支持** — 双摇杆、15 个按钮（通过 GLFW 手柄 API）
- **Sable 物理兼容** — 实体 yaw 与子世界旋转同步
- **按 `~` 下马**、**`TAB` 切换模式**、**`ESC` 打开菜单释放鼠标**

#### 📐 姿态传感器
通过节点图读取 sable 物理结构的姿态。

- **姿态节点** — 输出子世界的俯仰（pitch）和横滚（roll）
- **前方朝向节点** — 输出结构的全局朝向偏航/俯仰
- **世界视角节点** — 读取玩家在座椅上的绝对视角方向
- **姿态换算节点** — 在不同坐标系间转换 pitch/yaw/roll

#### 📡 3D全息显示雷达
一个**实时 3D 雷达扫描器**，在可配置的半径内检测实体和 sable 结构，以彩色小方块的形式显示在 XYZ 轴显示屏上。

- **实体扫描** — 检测玩家、生物和 Sable 结构，范围可配置（1-128 格）
- **目标分配** — 自动将检测到的目标分配给节点图中的 `TARGET_OUT` 节点，支持多目标和单目标模式
- **手动锁定** — 右键点击空中 blip 锁定特定目标；锁定的目标跨扫描保持
- **自动锁定** — 根据扫描模式自动分配最近目标
- **锁定距离** — 可配置最小锁定距离（0-128m）；距离小于此值的目标不会被锁定
- **显示设置** — 通过 GUI 设置面板调整 XYZ 偏移（左/右、上/下、前/后）、扫描范围 (R) 和显示比例 (S)
- **显示风格** — 两种视觉模式：**经典**（彩色 XYZ 轴线）和**全息**（白色中心方块 + 半透明蓝色底面，类似全息战术显示屏）
- **过滤开关** — 分别显示/隐藏玩家、生物和 Sable 结构
- **排除主机** — 可选择排除雷达所在的 Sable 结构
- **Sable 兼容** — 完整支持在移动/旋转的 Sable 结构上扫描；雷达位置和扫描框跟随结构移动
- **实时 GUI** — 专用设置面板带数字输入框，实时预览更新
- **自定义模型** — 完整 Blockbench 模型，带旋转扫描碟（BakedModel + RenderType.solid 完整光照）

**雷达专用节点：**
| 节点 | 说明 |
|------|------|
| `TARGET_OUT` | 输出分配目标的世界坐标 (X, Y, Z)、实体 ID 和距离。可多个 TARGET_OUT 节点用于多目标跟踪 |

**设置面板参数：**
| 参数 | 范围 | 说明 |
|------|------|------|
| 扫描范围 (R) | 1-128 | 检测半径（格） |
| 显示比例 (S) | 1-32 | XYZ 轴显示大小 |
| 锁定距离 | 0-128 | 目标锁定的最小距离 |
| X(左/右) | 浮点数 | 左右显示偏移 |
| Y(上/下) | 浮点数 | 上下显示偏移 |
| Z(前/后) | 浮点数 | 前后显示偏移 |

---

#### 📱 便携终端

一个**手持远程编辑器**，扫描附近的可编程方块并直接打开其原生界面进行远程编辑。

- **设备扫描** — 扫描 overworld 和 Sable 子世界中的可编程方块，范围可配置（1-256 格）。Sable 扫描使用服务端 `LevelPlot` chunk 迭代和旋转修正的世界坐标计算。
- **一键编辑** — 从列表选择任意设备，即时打开其原生 GUI（图编辑器、设置、像素编辑器、雷达控制等）。
- **扫描范围可编辑** — 数字输入框，值变更自动刷新，同一次游戏内范围保持。
- **支持全部 7 种方块** — 全息显示器、蓝图计算机、可编程计算机、雷达、控制座椅、姿态传感器、速度代理。
- **Sable 兼容** — 通过类加载器安全的接口检测正确识别 Sable 物理结构内的可编程方块。
- **3D 手持模型** — 自定义 Blockbench 模型，已调整 GUI 显示角度。

---

### BUS 总线节点系统

**Signal Bus** 是一个全局命名通道通信系统，允许计算机之间共享数据——类似发布-订阅消息总线。

#### 工作原理

1. **BUS_OUT 节点** 将输入值写入命名通道（如 `"power_grid"`）
2. **BUS_IN 节点** 从同一通道名称读取数据（任意位置、任意计算机）
3. 每个通道支持**频段**——命名的子字段，每个频段携带独立浮点数
4. 使用 `ConcurrentHashMap` 实现线程安全的跨计算机通信

#### BUS_OUT（总线输出）
| 功能 | 说明 |
|------|------|
| 通道注册 | 在全局 BAND_REGISTRY 中注册通道名和频段定义 |
| 引用计数 | 每个 BUS_OUT 增加引用计数；计数归零时自动清理 |
| 冲突检测 | 不同计算机的 BUS_OUT 使用相同通道名时，第二个被拒绝并标记为冲突 |
| 频段同步 | 频段定义同步到所有客户端供编辑器 UI 显示 |

#### BUS_IN（总线输入）
| 功能 | 说明 |
|------|------|
| 动态引脚 | 引脚数量匹配通道频段数；无频段则默认 1 个引脚 |
| 频段名 | 每个频段有用户可定义名称（如 "speed"、"direction"） |
| 一对多 | 多个 BUS_IN 可同时从同一通道读取 |

**应用场景：**
- 🏭 **工厂级控制** — 一台计算机发布指令，多台接收执行
- 📊 **数据分发** — 传感器采集数据，显示屏展示仪表盘
- 🔄 **跨计算机联动** — 多个子系统通过共享通道通信

---

### 节点参考（81 种）

| 分类 | 节点 |
|------|------|
| **数值** | 常量、红石输入、私有信号输入、总线输入 |
| **基础运算** | 加、减、乘、除、模运算、次幂、次方根、绝对值、向上取整、向下取整 |
| **高级运算** | **公式**、比较路由、**保留N位小数**、姿态换算（3入2出）、分割 |
| **三角函数** | 正弦、余弦、正切、反正弦、反余弦、反正切2、双曲正弦、双曲余弦、**平方根**、**自然对数**、**常用对数**、**指数**、**正割**、**余割**、**余切**、**角度解绕**、方向（3入3出） |
| **逻辑** | 大于、小于、大于等于、小于等于、等于、布尔（反转）、闸门、**或门** |
| **控制** | PID 控制器、动力 PID、限幅、映射范围 |
| **输出** | 红石输出、私有信号输出、转速控制、**总线输出** |
| **时序** | 延时、锁存器、T 触发器（可配置默认）、脉冲延长、循环、保险、累计器、连续积分器 |
| **显示** | TEXT（文字）、DATA（数值）、IMAGE（图片）、IMAGE_SEQUENCE（动画） |
| **操作输入** | 键盘按键（58键）、鼠标按键（左/右）、鼠标摇杆（X/Y）、手柄摇杆（LX/LY/RX/RY）、手柄按键（15键）、**手柄扳机（LT/RT）** |
| **传感器** | 世界视角（偏航/俯仰）、姿态（俯仰/横滚）、前方朝向（偏航/俯仰）、视角差（俯仰/偏航）、**加速度（X/Y/Z）**、**速度（X/Y/Z）**、**世界坐标（X/Y/Z）**、**目标输出（X/Y/Z/entityId/距离）** |
| **结构** | 封装 |
| **封装 I/O** | 输入、输出 |

#### 详细节点表

##### 数值
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| CONST | - | float | value | 输出常量值 |
| REDSTONE_IN | - | signal | 频率物品×2 | 从机械动力红石链接读取信号 |
| PRIVATE_IN | - | val | channel | 从命名通道读取浮点数 |
| BUS_IN | - | band×N | bus name | 从共享总线通道读取频段值 |

##### 基础运算
| 节点 | 输入 | 输出 | 说明 |
|------|------|------|------|
| ADD | A, B | float | A + B |
| SUB | A, B | float | A - B |
| MUL | A, B | float | A × B |
| DIV | A, B | float | A ÷ B（B=0 时返回 0） |
| MOD | A, B | float | A % B（取模） |
| POW | A, B | float | A 的 B 次幂 |
| ROOT | A, B | float | A 的 B 次方根（B=0 时返回 0） |
| ABS | in | float | 输入值的绝对值 |
| CEIL | in | int | 向上取整 |
| FLOOR | in | int | 向下取整 |

##### 高级运算
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| FORMULA | 动态（按脚本） | 动态（按@output） | script | **多行脚本编辑器** — 支持赋值（`var = expr`）、`@output` 命名输出、`--` 注释、`\` 续行。自动换行+拖选。函数：sin/cos/tan/asin/acos/atan2/sinh/cosh/sqrt/ln/log/exp/sec/csc/cot/abs。[用法见下](#公式脚本节点) |
| INTERP（比较路由） | A, B | A, B | - | A≥B 时 A 口输出 A-B，否则 B 口输出 \|B-A\| |
| ROUND | in | float | decimals | 保留N位小数（默认2位） |
| POSE_CONVERT | pitch_a, yaw_a, roll | pitch_b, yaw_b | - | 姿态换算（3入2出） |
| SPLIT | in | +out, -out | - | 正负分离：正数输出+，负数绝对值输出- |

##### 三角函数
| 节点 | 输入 | 输出 | 说明 |
|------|------|------|------|
| SIN | in | float | 正弦（度） |
| COS | in | float | 余弦（度） |
| TAN | in | float | 正切（度） |
| ASIN | in | float | 反正弦（度） |
| ACOS | in | float | 反余弦（度） |
| ATAN2 | y, x | float | y/x 的反正切（度） |
| SINH | in | float | 双曲正弦 |
| COSH | in | float | 双曲余弦 |
| SQRT | in | float | 平方根（负数返回0） |
| LN | in | float | 自然对数（≤0返回0） |
| LOG | in | float | 常用对数（≤0返回0） |
| EXP | in | float | e的指数 |
| SEC | in | float | 正割：1/cos(输入°) |
| CSC | in | float | 余割：1/sin(输入°) |
| COT | in | float | 余切：1/tan(输入°) |
| ANGLE_UNWRAP | in | float | 角度解绕，消除±180°跳变；有状态（记住上次输出） |
| DIRECTION | ax, ay, az, bx, by, bz | yaw, pitch, distance | 两点间世界空间的方向和距离 |

##### 逻辑
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| GT | A, B | bool | - | A > B → 1 |
| LT | A, B | bool | - | A < B → 1 |
| GE | A, B | bool | - | A >= B → 1 |
| LE | A, B | bool | - | A <= B → 1 |
| EQ | A, B | bool | - | A = B → 1 |
| OR | A, B | bool | - | A > 0.5 或 B > 0.5 → 1 |
| BOOL | in | bool | inverted | 输入 > 0 → 1, ≤ 0 → 0; inverted=1 时反转 |
| GATE | val, Open, Close, Tog | out | default | val 在开启时通过; Open/Close 设置状态, Tog 切换 |

##### 控制
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| PID | SP, PV | ctrl | kp, ki, kd, scale, ilimit | PID 控制器（设定值 vs 过程变量），抗积分饱和 |
| PID_POWER | SP, PV, base | power | kp, ki, kd, ilimit | 带基础动力和 PV 反馈的 PID |
| CLAMP | In, Min, Max | float | - | 限幅 |
| MAP | In, InMin, InMax, OutMin, OutMax | float | - | 映射范围 |

##### 输出
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| REDSTONE_OUT | In | - | 频率物品×2 | 将信号写入机械动力红石链接（限幅 0~15） |
| PRIVATE_OUT | val | - | channel | 将浮点数写入命名通道 |
| SPEED_CTRL | speed, dir | rpm | - | 设置转速控制器的 RPM; dir>0.5 时反转方向 |
| BUS_OUT | band×N | - | bus name | 将输入值写入共享总线通道 |

##### 显示（仅全息显示器专用）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| TEXT | - | - | text, color | 显示文字内容 |
| DATA | val | - | color | 显示输入浮点数值 |
| IMAGE | X, Y | - | imageData | 16×16 像素图片，XY 信号驱动位置 |
| IMAGE_SEQUENCE | X, Y, frame | - | frames, fps | 多帧动画，frame 输入切换帧 |

##### 时序（仅编程计算机）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| DELAY | in | out | ticks | 延时 N tick 后输出 |
| LATCH | S, R | q | default | S≥1 置位，R≥1 复位，保持；可配置初始状态 |
| T_FLIPFLOP | in | tog | default | 上升沿翻转输出；可配置初始状态 |
| PULSE_EXTEND | in | pulse | ticks | 输入高电平时脉冲延长 N tick |
| LOOP | in | clk | count, interval | 收到触发后每 interval tick 输出脉冲，重复 count 次 |
| FUSE | in | pulse | cooldown | 收到信号→2 tick 脉冲→冷却 N tick |
| ACCUMULATOR | +, - | val | step | 上升沿触发；+ 加 step，- 减 step |
| INTEGRATOR | +, -, clear | val | step, interval, limit | 每 interval tick 累计；+/- 同时有效→保持；clear 清零；上限 [0, limit] |

##### 操作输入（控制座椅专用）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| KEYBOARD | - | 1/0 | key | 绑定键盘按键（58键），按下输出 1 |
| MOUSE_JOYSTICK | - | X, Y | - | 鼠标增量输出 -1~1（摇杆模式） |
| MOUSE_BUTTON | - | L, R | - | 鼠标左/右键状态 |
| GAMEPAD_JOYSTICK | - | LX, LY, RX, RY | - | 手柄双摇杆 -1~1 |
| GAMEPAD_BUTTON | - | 1/0 | button | 手柄按键（15键） |
| GAMEPAD_TRIGGER | - | LT, RT | - | 手柄模拟扳机（0.0 ~ 1.0） |

##### 传感器（姿态传感器 / 控制座椅）
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| WORLD_VIEW | - | yaw, pitch | - | 玩家世界视角方向 |
| ATTITUDE | - | pitch, roll | - | 子世界姿态（俯仰/横滚） |
| FORWARD | - | yaw, pitch | - | 子世界前方朝向 |
| VIEW_ANGLE | - | pitch, yaw | - | 玩家视角差（视角差模式） |
| ACCELERATION | - | X, Y, Z | - | 结构本地加速度（前/后、上/下、左/右），20Hz 差分计算 |
| VELOCITY | - | X, Y, Z | - | 结构本地速度（前/后、上/下、左/右），×2 换算为 m/s |
| POSITION | - | X, Y, Z | offsetX, offsetY, offsetZ | 世界坐标位置（可配置偏移） |
| TARGET_OUT | - | X, Y, Z, entityId, distance | - | 雷达目标输出（仅雷达可用） |

##### 结构
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| ENCAPSULATION | 动态 | 动态 | - | 在单个节点内嵌套子图；双击进入子图编辑器 |

##### 封装 I/O
| 节点 | 输入 | 输出 | 参数 | 说明 |
|------|------|------|------|------|
| ENCAP_INPUT | - | float | name | 封装节点的外部输入引脚 |
| ENCAP_OUTPUT | float | - | name | 封装节点的外部输出引脚 |

---

### 公式脚本节点

FORMULA 节点现已升级为**多行脚本编辑器**（v1.2.0+），支持：

- **赋值**：`varName = expression` — 定义中间变量，后续行可复用
- **命名输出**：`@output varName` — 声明自定义名称的输出引脚
- **注释**：`-- 文字` — 以 `--` 开头的行被忽略
- **行续接**：行尾加 `\` 将下一行合并到当前行
- **默认输出**：未声明 `@output` 时，最后一行独立表达式作为输出

**示例 — 弹道方程：**
```
-- 弹道计算
dx = X1 - X0
dz = Z1 - Z0
w = sqrt(dx*dx + dz*dz)
secTheta = 1 / cos(THETA)
y = (99 * secTheta / (20 * N) + tan(THETA)) * w + 99 * ln(1 - 2 * (w * secTheta - K) / (199 * N)) / (20 * ln(100/99)) - 99 * K / (20 * N) + 2
@output y
@output w
@output secTheta
```
生成 **7 个输入引脚**（X1, X0, Z1, Z0, THETA, N, K）和 **3 个输出引脚**（Y, W, SEC_THETA）。

**编辑器快捷键：** Enter = 换行，↑↓ = 跨行，Home/End = 行首/尾，Ctrl+A = 全选，拖拽鼠标 = 选择文本。

---

### 使用方法

1. **放置**任意一个方块
2. **右键**打开节点编辑器
3. **右键空白处**打开添加节点菜单（支持分类折叠）
4. **左键节点**编辑参数
5. **从输出端口拖拽到输入端口**连接节点
6. **按 X 键**删除节点（悬停其上），或 **TAB + 左键**点击连线删除
7. 点击 **Compile** 编译，然后点击 **Run** 运行

**操作指南：**
| 操作 | 方法 |
|------|------|
| 打开节点菜单 | 右键空白处 |
| 编辑参数 | 左键节点，再点击 ▶ |
| 连接节点 | 从输出端口拖到输入端口 |
| 删除节点 | **X 键**（悬停节点上） |
| 删除连线 | **TAB + 左键**点击连线 |
| 删除选中节点 | Delete / Backspace |
| 框选节点 | TAB + 左键拖拽 |
| 切换选中 | TAB + 左键点击节点 |
| 拖拽移动选中 | TAB + 左键拖拽已选中节点 |
| 复制节点 | Ctrl + D |
| 展开/折叠编辑区 | 点击节点标题的 ▶ / ▼ |
| 颜色自定义 | 点击 **Style** 按钮 |
| 网格吸附开关 | 点击 **Grid** 按钮 |
| 缩放 | 滚轮 |
| 平移画布 | 右键拖拽 |
| **控制座椅 — 乘坐** | 右键点击座椅（空手） |
| **控制座椅 — 打开编辑器** | Shift + 右键点击座椅 |
| **控制座椅 — 下马** | 按 **`~`** 键 |
| **控制座椅 — 切换模式** | 按 **`TAB`** 键 |
| **控制座椅 — 暂停/释放鼠标** | 按 **`ESC`** 键 |

---

### 蓝图兼容
全部六种方块完全支持**机械动力的蓝图大炮（Schematicannon）**。节点图、参数和运行状态在保存和放置蓝图时都会**完整保留**，不会丢失数据。

> 采用 Create 官方 `IMergeableBE` 接口和 `SafeNbtWriter` 注册，确保蓝图数据的可靠保存与恢复。

---

### 技术亮点

| 特性 | 说明 |
|------|------|
| ⚡ **拓扑排序求值** | 节点图按拓扑顺序求值，支持 O(1) 输入查询缓存 |
| 🚀 **GC 友好** | 重用 `GraphEvaluator` 实例，减少每 tick 垃圾回收压力 |
| 🔄 **私有信号总线** | 全局命名通道通信，跨计算机联动 |
| 🎯 **精准反射** | 通过反射直接设置转速控制器的内部字段 |
| 🧹 **PID 抗积分饱和** | 完整的 SP/PV 双输入 PID，可配置 kp/ki/kd/scale/ilimit，积分上限钳制 |
| 🛡️ **环检测** | 编译时自动检测循环引用并阻止运行 |
| 🎮 **GLFW 原始输入** | 控制座椅通过 GLFW 直接读取键盘/鼠标/手柄，绕过 Minecraft 按键绑定系统 |
| 🔄 **Sable 物理集成** | 控制座椅和姿态传感器实现 `BlockEntitySubLevelActor` 接口读取子世界姿态 |

---

### 方块属性
所有 6 个方块共享一致的属性：

| 属性 | 值 |
|------|-----|
| **硬度** | 1.0（空手可破坏，无需工具） |
| **空手破坏** | 掉落方块物品**不含 NBT**（全新方块） |
| **扳手（右键）** | 旋转方块（循环 FACING 方向） |
| **扳手（Shift + 右键）** | 收回方块**保留完整 NBT**（节点图、运行状态、PID 参数） |



### Sable 物理集成

本模组与 **Sable 物理引擎** 深度集成。控制座椅和姿态传感器专为在旋转的物理结构上工作而设计。

#### 工作原理

两个方块都实现了 `BlockEntitySubLevelActor` — Sable 用于参与子世界物理模拟的方块实体接口。当方块放置在 sable 子世界中时：

1. **`sable$physicsTick()`** 每个物理 tick 触发（与服务端 tick 并发运行）
2. 子世界的 **逻辑姿态**（方向四元数）通过 `subLevel.logicalPose()` 读取
3. 使用 JOML 的 `getEulerAnglesYXZ()` 提取欧拉角（匹配 Minecraft 的 YXZ 旋转约定）
4. 偏航角从 JOML 的逆时针正方向转换为 Minecraft 的顺时针正方向
5. 计算**相对旋转**（减去初始子世界偏移），使静止结构读取为 0° 偏差

#### 线程安全

Sable 物理线程和 Minecraft 服务端线程并发运行。共享字段（`cachedSubYaw`、`cachedSubPitch`、`cachedSubRoll`、`hasSubPose`、`cachedBlockFacingYaw`、`initialSubYaw`）都标记为 **`volatile`** 确保跨线程可见性。

#### 控制座椅 + Sable

| 特性 | 说明 |
|------|------|
| **实体 yaw 同步** | 座椅实体 yaw 追踪子世界旋转（`blockFacing - relativeYaw`），玩家视角随结构转动 |
| **摇杆模式** | 玩家视角锁定到实体 yaw，自动跟随物理结构旋转 |
| **视角差模式** | 客户端发送 `playerYaw - vehicleYaw` 差值，服务端用实体 yaw 重建绝对世界偏航 |
| **相对旋转追踪** | 首次 physics tick 记录初始子世界 yaw，后续旋转测量为与初始值的偏移 |

#### 姿态传感器 + Sable

| 节点 | 数据来源 | 说明 |
|------|----------|------|
| **ATTITUDE** | `subLevel.logicalPose().orientation()` → `getEulerAnglesYXZ()` | 输出俯仰 pitch（X）和横滚 roll（Z） |
| **FORWARD** | 方块朝向向量经子世界四元数旋转 | 输出结构在世界空间中的前方偏航/俯仰 |
| **WORLD_VIEW** | 玩家绝对偏航（视角差 + 实体 yaw） | 仅在视角差模式更新，摇杆模式冻结 |

#### 无 Sable 时

**Sable 是可选的。** 未安装 sable 时：

| 组件 | 行为 |
|------|------|
| 控制座椅 | **完全可用** — 键盘/鼠标/手柄输入、按键绑定、两种输入模式、ESC 菜单 |
| 姿态传感器 | **部分可用** — 节点编辑器、所有数学/逻辑/输出节点、红石 I/O 正常工作。ATTITUDE/FORWARD/WORLD_VIEW 节点输出 **0**（无子世界姿态数据） |

---

### 合成配方

| 方块 | 材料 |
|------|------|
| **蓝图计算机** | 无线红石信号终端 ×2 + 精密构件 + 玻璃板 ×2 + 中继器 + 比较器 + 黄铜外壳 ×2 |
| **转速代理控制器** | 黄铜锭 ×4 + 齿轮 + 玻璃板 ×2 + 比较器 + 安山岩外壳 |
| **编程计算机** | 安山岩外壳 ×4 + 中继器 + 玻璃板 ×2 + 比较器 + 安山合金 |
| **控制座椅** | 重质测重压力板 ×1 + 铁锭 ×2 + 黄铜机壳 ×1 + 红石 ×1 + 无线红石信号终端 ×4 |
| **姿态传感器** | 铁锭 ×6 + 中继器 ×1 + 比较器 ×1 + 黄铜机壳 ×2 |
| **全息显示器** | 无线红石信号终端 ×2 + 精密构件 + 玻璃板 ×2 + 黄铜机壳 + 荧石粉 ×2 |
| **3D全息显示雷达** | 全息显示器 ×2 + 铁锭 ×4 + 黄铜机壳 ×1 + 红石块 ×2 |

*(需要 JEI 模组在游戏中查看)*

---

### 创意用法

| 用途 | 说明 |
|------|------|
| 🏭 **智能工厂控制** | 使用 PID 节点实现转速自动调节 |
| 🔄 **自动化时序** | 构建复杂的自动化序列 |
| 🎛️ **多级联动** | 多台计算机通过私有信号总线协同工作 |
| 🖥️ **实时工厂仪表盘** | 用全息显示器实时展示传感器数据和运行指标 |
| 🎯 **无线远程控制** | 结合红石链接网络实现远程控制 |
| ⚙️ **速度匹配** | 转速代理控制器让转速精确匹配 |

---

### 常见问题

**Q: 节点编辑器响应迟缓？**  
A: 检查是否节点过多或 PID 运算复杂，建议控制持续运行的 PID 数量。

**Q: 转速代理控制器不工作？**  
A: 确保转速代理控制器放置在转速控制器的相邻 6 个面之一。

**Q: 蓝图放置后计算机状态丢失？**  
A: 确保使用 Create 6.0.10+，本模组已注册完整的 NBT 保存/加载接口。

**Q: 计算机之间可以通信吗？**  
A: 可以！使用私有信号输入/输出节点配合命名通道，蓝图计算机和编程计算机可以跨距离交换浮点数值。

---

### 安装

1. 安装 **NeoForge 21.1.233+**
2. 安装 **Create 6.0.10+**
3. 将模组的 `.jar` 文件放入 `mods` 文件夹
4. 启动游戏！

---

### 源代码

📦 **GitHub 仓库**：[https://github.com/y15173334444/create-schematic-compute](https://github.com/y15173334444/create-schematic-compute)

本项目完全开源，基于 **MIT 许可证**。欢迎提交 Issue 和 Pull Request！

---

### 许可

MIT License © 2026 StarryNight_Luo

---

## 📝 Changelog

### v1.2.3 — Server Crash Fix + Package Rename
- **🐛 Dedicated Server crash fix** — removed client-class imports (`Screen`, `Minecraft`, `PortableTerminalScreen`) from common-side code (`ScanSableResponsePacket`, `PortableTerminalItem`). Replaced with static `Consumer` injection pattern wired during `FMLClientSetupEvent`. Fixes #4.
- **📦 Package rename** — `com.example.create_schematic_compute` → `io.github.y15173334444.create_schematic_compute` (group, mixins, all 80 Java sources)

### v1.2.2 — Portable Terminal + Layer Panel + Undo/Redo
- **📱 Portable Terminal** — new handheld item to remotely discover and edit programmable blocks. Scans overworld and Sable sub-level devices within configurable range (1–256 blocks). One-click opens the block's native GUI.
- **🔍 Sable sub-level scanning** — server-side scan via `LevelPlot.getLoadedChunks()` with rotation-corrected world position calculation. Classloader-safe interface detection handles Sable's jarjar isolation.
- **🖥️ Remote block GUI** — all 7 programmable blocks open their native interface through the terminal. Virtual menu system with `getBE()` fallback for blocks at any location.
- **🖼️ Photoshop-style layer panel** — redesigned Holographic Monitor display editor with 108px-wide layer panel, 30px rows, and 24×24 component thumbnails (TEXT preview, DATA value, IMAGE 16×16 pixels, IMAGE_SEQUENCE current frame). Drag-and-drop layer reordering with ghost row, amber drop indicator, and auto-scroll. Layer order persists via NBT.
- **↩️ Undo/Redo system** — Ctrl+Z / Ctrl+Y support for graph editor (add/delete nodes, connections, duplicate) and pixel editor (paint operations, new frames). Graph-level undo uses NBT snapshots with 50-step history. Pixel editor has independent pixel-data-only undo stacks.
- **🖥️ Display editor fixes** — selection highlight now renders on top of all elements. Clicking empty display area starts dragging the already-selected component (no more lost selections). 3D renderer sorts by layerIndex with per-element Z-offset to prevent z-fighting.
- **🛠️ Radar fixes** — removed aggressive `validateSableCache()` that cleared Sable pose every tick. Bootstrap now uses `boundingBox()` for precise sub-level matching. `onLoad()` clears cached coordinates to fix NBT-copied radar stale data.
- **🧹 Code cleanup** — portable terminal streamlined (7 dead packet files removed, screen code -52%). All screens unified `toggleRunning()` pattern for local state update.
- **🎨 3D terminal model** — custom Blockbench handheld model with GUI display angles.

### v1.2.1
- **⚡ GUI performance optimization (Phase 1)** — eliminated per-frame allocations: pooled Vector3f/Quaternionf in radar renderer, cached redstone input lists, replaced String.format with fast formatters (ff0/ff1/ff2/ff3/hex8), fixed MultiLineEditBox O(n²) substring allocation via plainSubstrByWidth
- **🎨 Atomic color palette** — 16 themeable colors stored in single volatile int[] array; atomic swap eliminates cross-thread color tearing during theme switch
- **🏗️ Dirty flag framework (Phase 2)** — NodeGraph.graphGeneration counter for cache invalidation; markDirty() wired into all mutation paths (drag, param edit, expand/collapse, recompile, pixel paint)
- **📐 MonitorScreen display cache** — collectDisplayElements() cached when graph/screen static; auto-invalidates on drag, running state change, or generation bump
- **📏 Precision hardening (Phase 3)** — new GeometryConstants with 22 shared layout constants; deduplicated clampImageNorm, elemRotAABB, color palette; unified FONT_BLOCK_SCALE across 2D GUI and 3D renderer; PALETTE_CELL inconsistency fixed (24→16); grid line sub-pixel jitter fixed (truncation→Math.round); MultiLineEditBox LINE_HEIGHT now reads font.lineHeight
- **🛠️ Radar Sable fixes** — removed 1,000,000 coordinate guard in tryBootstrapSableCache() that blocked initialization for schematic-placed radars; fixed RadarBlockEntitySable savedLevel fallback race during blueprint placement where both level and savedLevel were null simultaneously
- **🧩 DIRECTION node unlocked** — now visible in BlueprintScreen and ProgramComputerScreen (was defined but hidden by all screen filters)
- **📦 Radar loot table** — radar block now drops itself on break

### v1.2.0
- **Formula node → multi-line script editor** — MultiLineEditBox with word wrap, drag-select (Ctrl+A), Enter/arrow key navigation, Ctrl+V paste with `\r\n` normalization
- **Formula script syntax** — assignments (`var = expr`), `@output` named pins, `--` comments, `\` line continuation, dynamic I/O pins
- **7 new math function nodes** — SQRT, LN, LOG, EXP, SEC, CSC, COT + **Angle Unwrap** (stateful ±180° jump elimination)
- **3D Holographic Radar** — real-time radar scanner with configurable range, target locking, Classic/Holographic display styles, Blockbench model, rotating scanner disc
- **TARGET_OUT node** — radar target output (X/Y/Z/entityId/distance) for graph-driven radar processing
- **Identifier scanning** — variable names support `[a-zA-Z_][a-zA-Z0-9_]*` (X1/X0/Z1/Z0 style vars)
- **FORMULA node widened to 240px** — `nw()` dynamic node width + multi-column Trig menu (17 items → 2 cols)
- **E key fix** — printable keys intercepted to prevent inventory closure + pin auto-cleanup on variable removal
- **Performance** — dead code removed, ScriptParseResult cached, buildVisualLines dirty flag, EditPanel reuse
- **Radar recipe** — 2× Monitor + 4× Iron + Brass Casing + 2× Redstone Block
- **BUS system** — BUS_IN/BUS_OUT nodes, Bus Band system, BusChannelHelper, import/export, encapsulation state persistence, conflict auto-recovery
- **3D Holographic Radar** — real-time scanner, target locking, Classic/Holographic styles, Blockbench model, rotating disc, Sable structure scanning, recipe
- **World coordinate nodes** — VELOCITY, ACCELERATION, DIRECTION, POSITION, TARGET_OUT for sensors and radar
- **Various fixes** — Sable radar scanning, expand button hit test, output pin connection offset, `\r\n` normalization, formula display removed from collapsed node, bandsDirty perf, I18n skip, NBT compatibility

### v1.1.5
- **Add: LATCH edit panel** — configurable default set/reset state toggle with real-time current state display
- **Add: PRIVATE_IN / PRIVATE_OUT nodes** — Program Computer now supports private signal channels for cross-block communication
- **Add: RuntimeState sync packet** — flipflopStates synced server→client on change for real-time UI display in multiplayer
- **Fix: Recompile state reset** — GATE, T_FLIPFLOP, and LATCH current state now resets to configured default on recompile
- **Fix: Edit panel live state display** — GATE/T_FLIPFLOP/LATCH current state now updates in real-time during execution (single-player shared object + multiplayer network sync)
- **Fix: World reload state persistence** — Program Computer and Blueprint Computer now correctly restore flipflopStates and pulseTimers across world reloads (high-precision circuits survive save/quit)
- **Fix: RuntimeState load consistency** — all 6 block entities audited; Blueprint now loads full runtime state; SpeedProxy clears pidState on graph change
- **NBT compatibility: v1→v2 migration** — old LATCH saves (params.length=0) auto-upgraded to new format (params[2] with defaults)

### v1.1.4
- **Add: Velocity node** — structure-local X/Y/Z velocity from sable physics (Control Seat + Attitude Sensor), ×2 scaled to m/s
- **Add: Universal param input pins** — all numeric EditBox params now expose input pins inside the edit panel (PID kp/ki/kd, DELAY ticks, LOOP count/interval, etc.)
- **Add: CLAMP/MAP param pins** — min/max and in/out range pins moved to edit panel, with EditBox fallback defaults
- **Add: Edit panel pin rendering** — param input pins rendered as small circles on the left of each EditBox; connected pins shown dimmed
- **Add: Expanded state NBT persistence** — node expand/collapse state saved to NBT and restored on world reload
- **Add: NBT compatibility layer** — data format versioning (`NbtVersions`), v0→v1 migration (`GraphMigration`), stable NodeType string IDs for forward compatibility
- **Add: Runtime state persistence** — PID integrals, delay queues, flipflop states, and pulse timers saved to NBT via `RuntimeState`, surviving save/reload without data loss
- **Fix: Encapsulation node copy** — Ctrl+D now deep-copies sub-graph contents via `shallowCopyWithNewId()` and `NodeGraph.copy()`
- **Fix: Wire drag connects to nearest pin** — no longer connects to all nearby pins simultaneously
- **Fix: TAB+click deletes nearest connection** — finds globally nearest connection instead of first match
- **Fix: Connection bezier positions** — correctly target functional pins on node body and param pins in edit panel
- **Fix: Edit panel Y coordinate mismatch** — unified `functionalInputs()` for body layout, fixing broken edit area click detection
- **Fix: Velocity/acceleration block-facing rotation** — properly rotates to block-local frame from sub-world-local
- **Fix: Non-param fields pin rendering** — FORMULA, PRIVATE_IN/OUT, ENCAP_INPUT/OUTPUT no longer show false pin circles
- **Fix: Block filtering** — VELOCITY node correctly excluded from Blueprint Computer, included in Control Seat and Attitude Sensor
- **Perf: `NodeGraph.hasInputConnection()`** — O(1) input pin connection check via input cache
- **Refactor: `shallowCopyWithNewId()`** — unified deep-copy covering all node fields (subGraph, formula, image pixels, display layout)

### v1.1.3
- **Add: OR Gate** — 2 inputs (A,B), 1 output (bool), Logic category, Blueprint Computer
- **Add: GE / LE** — Greater Than or Equal / Less Than or Equal comparison nodes in Logic category
- **Add: ROUND** — round to N decimal places (Advanced Math, Blueprint only)
- **Add: Continuous Integrator** — 3 inputs (+,-,clear), configurable step/interval/limit, Program Computer
- **Add: Acceleration** — structure-local X/Y/Z acceleration from sable physics (Control Seat + Attitude Sensor)
- **Add: Gamepad Trigger** — analog LT/RT outputs (0.0~1.0) for Control Seat
- **Add: IMAGE/IMAGE_SEQUENCE rotation input** — signal-driven rotation with per-axis moveScale (X/Y), rotationScale, invertX/Y toggles
- **Add: T_FLIPFLOP edit panel** — configurable default on/off state toggle
- **Add: i18n** — ~130 new translation keys for node edit panels, pin labels, toolbar, toggles (zh_cn + en_us)
- **Fix: GAMEPAD_BUTTON binding** — moved from keyPressed() to frame-polling with edge detection; works with mapping software
- **Fix: Gamepad trigger clamp** — axes clamped to [0,1] for phone/Bluetooth gamepads
- **Fix: IMAGE_SEQUENCE frame display** — 3D renderer now reads frame input pin and selects correct frame
- **Fix: Monitor display rotation** — center-based rotation with correct direction matching GUI preview
- **Fix: Double checkmark** — removed duplicate ✔ from BOOL/GATE toggle i18n strings
- **Fix: LATCH pin label** — resolved duplicate `r` key conflict between LATCH Reset and Mouse Right
- **Perf: Acceleration** — pure float trig instead of JOML object allocations, no GC stutter on sable structures

### v1.1.2
- **Add: GATE node** — signal gate with 4 inputs (value/open/close/toggle) and NBT-persistent state; available on Blueprint and Program computers
- **Fix: Node connection culling** — connections on the left/top side of the GUI are no longer incorrectly culled
- **Fix: Monitor GUI rotation** — display element rotation direction now matches between GUI editor and 3D screen
- **Fix: Monitor GUI margins** — component positions in GUI now include the 0.04-block bezel margin matching the 3D screen
- **Fix: Monitor IMAGE clamping** — rotated IMAGE elements use AABB-aware edge clamping matching the 3D renderer
- **Fix: Monitor settings data loss** — changing screen settings no longer resets display element positions/rotations
- **Fix: Monitor live preview** — display area updates in real-time while editing screen settings
- **Fix: ACCUMULATOR crash** — placing accumulator node on Program Computer no longer crashes the game

### v1.1.1
- **Add: Encapsulation node** — nest sub-graphs inside a single node, double-click to expand, 48→49 types
- **Add: Monitor Redstone Input** — Holographic Monitor now reads Redstone Link network signals via REDSTONE_IN node
- **Fix: Control Seat view jump** — smooth transition between Joystick and View Angle modes (Mixin-based raw mouse delta capture)
- **Fix: Monitor redstone registration** — late-registered listeners now receive initial signal state via force-resend
- **Fix: Monitor 3D renderer** — correctly evaluates REDSTONE_IN nodes, evaluator caching for 60+ FPS performance
- **Perf: Code audit** — NaN guards on all arithmetic nodes (ADD/SUB/MUL/MAP/INTERP), FORMULA compilation cache, Sensor reflection cache, volatile thread safety
- **Refactor: RedstoneLinkHelper** — ~400 lines of duplicated redstone link code deduplicated across Blueprint, ProgramComputer, ControlSeat, and Sensor
- **Refactor: GraphBlockEntity interface** — replaces 6-branch instanceof chains in BlueprintSavePacket and BlueprintTogglePacket
- **Mixin infrastructure** — added Mixin AP for Entity.turn() interception in Control Seat

### v1.1.0
- **Add: Holographic Monitor block** — floating 3D display screen with pixel editor, display mode, signal-driven movement
- **Add: Control Seat block** — sit-able controller with keyboard/mouse/gamepad input, 58 assignable keys, dual input modes (Joystick / View Angle), Sable physics compatibility
- **Add: Attitude Sensor block** — reads Sable physics structure orientation via ATTITUDE/FORWARD/WORLD_VIEW nodes
- **Add: 14 new node types** — 4 Display (TEXT, DATA, IMAGE, IMAGE_SEQUENCE), 10 Input/Sensor (KEYBOARD, MOUSE_JOYSTICK, VIEW_ANGLE, MOUSE_BUTTON, GAMEPAD_JOYSTICK, GAMEPAD_BUTTON, WORLD_VIEW, ATTITUDE, FORWARD, SPLIT, POSE_CONVERT), bringing total from 24→48
- **Add: FORMULA node** — custom math expressions with multi-letter variable names, auto-created input pins
- **Add: Accumulator node** — +/- dual-input rising-edge counter
- **Add: Pixel editor** — 16×16 grid with 2-column palette, HEX input, multi-frame animation
- **Add: Color customization** — 16-color theming, ARGB text color for TEXT/DATA nodes
- **Add: IWrenchable support** — wrench rotation and shift+wrench NBT pick-up for all 6 blocks
- **Add: Multilingual support** — full EN/ZH localization for Monitor and all GUIs
- **Change: Create-style warm metallic GUI palette** (brass/copper/steel)
- **Change: Redstone output clamped to 0-15**
- **Fix: World reload bug** — all blocks work correctly after save/quit/reload
- **Fix: SignalBus cross-world pollution** — static state properly cleared on server stop
- **Fix: NaN propagation** — guarded POW, ROOT, PID against NaN/Infinity
- **Fix: SpeedProxy shared static PID map → per-instance**

### v1.0.0
- **Initial release**: 3 programmable computers (Blueprint, Speed Proxy, Program)
- 24 node types across 6 categories (Values, Math, Logic, Control, Output, Sequential)
- Visual node-based graph editor with drag-connect workflow
- Create Redstone Link network integration
- Private Signal Bus for cross-computer communication
- Schematicannon compatibility via IMergeableBE + SafeNbtWriter
- Add: Create `IMergeableBE` interface for reliable data restoration
- Add: `SafeNbtWriter` registration for Create schematic compatibility
- Add: POW (A^B) and ROOT (B-th Root of A) math nodes
- Add: BOOL node with invert toggle for logic control
- Add: SPEED_CTRL direction control (2nd input pin `dir`)
- Add: TAB + box select, multi-drag, multi-copy, multi-delete
- Add: ABS (Absolute Value) and Comparison Router (|A-B|) nodes
- Change: Remove PV input from PID and PID_POWER nodes
- Change: Rename Interpolation → Comparison Router

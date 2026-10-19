## Engine Recon Automation (Unity, Unreal, etc.)
Understanding and automating engine-level reconnaissance is critical for every red teamer, cheat dev, or reverse engineer. Each modern engine (Unity, Unreal, CryEngine, etc.) provides predictable metadata, method tables, and memory layouts that you can scan, dump, or script around for massive leverage.

---

### Goals of Engine Recon

- Automatically locate key game logic (health, damage, abilities, inventory)
- Identify functions to hook or patch
- Map scripting engines (Mono, Lua, IL2CPP)
- Dump class hierarchies (e.g. Player, Entity, Ability)
- Locate rendering functions, timers, or input handlers
- Script cheat tables or Frida hooks dynamically

---

### Unity Engine (Mono / IL2CPP)

### Identifying Unity Games

**File Indicators:**

- UnityPlayer.dll, global-metadata.dat, Assembly-CSharp.dll
- Directory: /Managed/, /Data/, /MonoBleedingEdge/
- Presence of il2cpp_data/ for IL2CPP builds

**Memory Indicators:**

```bash
strings -n 10 game.exe | grep "UnityEngine"
```

UnityVersion tags in binary or Player.log.

---

### Mono Runtime Recon

**Steps:**

- Attach Cheat Engine or MonoMod tools
- Go to Mono → Activate Mono Features
- Use Dissect Mono to list all classes and methods
- Hook method using mono_findMethod

**Auto-dumper:**

```lua
local c = mono_enumDomains()
for _, domain in pairs(c) do
    local assemblies = mono_enumAssemblies(domain)
    for _, asm in pairs(assemblies) do
        print("Assembly:", mono_getAssemblyName(asm))
    end
end
```

---

### IL2CPP Automation

**Tools:**

- Il2CppDumper (CLI/GUI)
- IDA Plugin: Il2CppInspector
- ScyllaHide + CE for live memory scans

**Process:**

1. Dump global-metadata.dat + GameAssembly.dll
2. Run:
```sh
Il2CppDumper GameAssembly.dll global-metadata.dat output/
```
3. Look for key class mappings: Player::TakeDamage, Inventory::AddItem, etc.

**Bonus:**

- Use IDA Pro + FLIRT to auto-rename IL2CPP methods
- Create .sig from Unity 2021.3 base headers for auto-tagging

---

### Unreal Engine Recon (UE3/UE4/UE5)

### Identifying Unreal Games

**Static Indicators:**

- UE4Game.exe, UE5Game.exe, GEngine.dll, UnrealPak.exe
- .pak files in /Content/ or /Paks/
- UObject, UFunction, FString patterns in memory

**Runtime Signatures:**

| Target         | AOB Signature                               |
|----------------|---------------------------------------------|
| GObjects       | 48 8B 05 ?? ?? ?? ?? 48 8B 0C C8             |
| GNames         | 48 8D 0D ?? ?? ?? ?? E8 ?? ?? ?? ?? 33 C0    |
| ProcessEvent   | E8 ?? ?? ?? ?? 48 8B CF E8 ?? ?? ?? ??       |

Use pymem + AOB scan or CE Lua script to resolve dynamically.

---

### Auto SDK Generation

Use UE4 SDK Generator:

```sh
UE4Dumper.exe -pid <target_pid> -dump
```

Generates: `Classes.hpp`, `Offsets.hpp`, `Functions.cpp`

Load in IDA to cross-ref symbols and write your own internal ProcessEvent() hook.

---

### Auto Object Dumper (Python + pymem)

```python
from pymem import Pymem

pm = Pymem("game.exe")
gobjects = pm.read_int(0x12345678)  # Found via sig scan
for i in range(1024):
    obj_ptr = pm.read_int(gobjects + i * 4)
    name = pm.read_string(obj_ptr + 0x18)
    print(f"[+] Object {i}: {name}")
```

---

### GNames and GObjects Pattern Script in IDA or Ghidra


```python
# Ghidra - find GNames
pattern = b"\x48\x8B\x05"
findBytes(currentProgram, pattern)
```

---

### UE4 .ini Logging Hack (Optional)

Enable rich logging for network or events:

```ini
[Core.Log]
LogNet=VeryVerbose
LogNetTraffic=VeryVerbose
LogOnline=VeryVerbose
```

Also enable:

```ini
[/Script/Engine.RendererSettings]
r.DebugDraw = 1
```

---

### WebAssembly and Browser Recon via WebGL

### Static and Runtime Tools

- wasm-decompile (binaryen)
- wasm2wat (WABT)
- Chrome DevTools → Memory panel → WebAssembly.Instance.exports
- Hook WebAssembly.instantiateStreaming

### Instrumentation Example (DevTools Console)

```js
const original = WebAssembly.instantiate;
WebAssembly.instantiate = async function(buffer, importObj) {
    console.log("[+] Hooked WASM instantiate");
    return original.call(this, buffer, importObj);
};
```

Or use:

```js
Function = new Proxy(Function, {
    apply(target, thisArg, args) {
        console.log("Eval:", args[0]);
        return Reflect.apply(...arguments);
    }
});
```

---

### .wasm Mapping

- wasm-decompile
- radare2 -AA file.wasm
- ghidra_wasm_plugin

Reverse-engineer exports: Identify heal(), moveTo(), attack().

---

### Lua Engine Recon

### What to Hook

- lua_getglobal, lua_setglobal, lua_pcall
- Enumerate Lua stack and global table

```c
int n = lua_gettop(L);
lua_pushnil(L);
while (lua_next(L, LUA_GLOBALSINDEX)) {
    printf("%s\n", lua_tostring(L, -2));
    lua_pop(L, 1);
}
```

### Dynamic Lua Hijacking (Frida)

```js
Interceptor.attach(Module.findExportByName(null, "lua_pcall"), {
    onEnter(args) {
        console.log("Calling Lua:", args[1]);
    }
});
```

---

### Tools to Include

| Engine | Tool                        | Purpose                          |
|--------|-----------------------------|----------------------------------|
| Unity  | Il2CppDumper, AssetRipper   | Dump C# classes, metadata        |
| Unreal | UE4Dumper, SDK Gen          | Generate headers, locate hooks  |
| WebGL  | wasm-decompile, DevTools    | Export analysis, JS interop      |
| Lua    | Frida, LuaJIT tools         | Dump globals, hook logic         |

---
### Engine-Specific Signatures
Use these indicators to fingerprint game engines and enable targeted reversing strategies.

| Engine           | Indicator                      | Signature / Pattern                              |
|------------------|-------------------------------|--------------------------------------------------|
| Unity (IL2CPP)   | global-metadata.dat, libil2cpp.so | .data section with metadata blob              |
| Unity (Mono)     | Assembly-CSharp.dll            | IL code; browse via dnSpy / dotPeek              |
| Unreal Engine    | UObject::GObjects, GNames      | 48 8B 05 ?? ?? ?? ?? 48 8B 0C C8                  |
| CryEngine        | CrySystem.dll, CEntity::Update() | Export table symbols or IDA auto-analysis     |
| Godot            | .gd scripts, main_loop strings | Custom bytecode and scene structure patterns     |

### Obfuscated Binary Detection and Unpacking
**High-Entropy Sections (VMProtect, Themida, Enigma)**

```sh
binwalk -E target.exe
```

**IL2CPP Metadata Fingerprint (Unity)**
```python
with open("global-metadata.dat", "rb") as f:
    header = f.read(4)
    if header == b"\xAF\x1B\xB1\xFA":
        print("[+] IL2CPP Metadata Detected")
```

**Unpacking VMProtect / Themida**
- Identify loader stub (jmp short, jmp dword ptr fs)
- Trace decrypt stub via x64dbg + ScyllaHide
- Dump real .text from memory using Scylla
- Rebuild IAT with PE-bear or x64dbg IAT Rebuilder

### C and C++ RTTI and Symbol Salvage

```c
// IDA script: Recover class names
auto rtti = get_rtti_struct(ea);
```


---
## Engine-Specific Hacks

Target game engines with tailored exploits.

### Core Techniques

- **Unity**: Patch `Assembly-CSharp.dll`, hook Mono runtime.
- **Unreal**: Inject .pak files, hook `UFunction::ProcessEvent`.
- **GameMaker**: Modify `.yy` / `.yyp` and inject via `YYDebug`.
- **WebGL/WASM**: Use `wasm-decompile`, optimize with `wasm-opt`.
- **Lua/Mono**: Inject scripts, hook `Assembly.Load`.

---

### Engine-Specific Exploits

- **Unreal Engine 5**:
  - Dump `GObjects`/`GNames` using pattern scan: `48 8B 05 ?? ?? ?? ?? 48 8B 0C C8`
  - Inject `UGameplayStatics::ExecuteConsoleCommand`

- **Unity**:
  - Dump IL2CPP with `Il2CppDumper` + `Ghidra`
  - Hijack Mono JIT via `mono_jit_compile_method`

- **Advanced**:
  - Shader Replacement for wallhacks
  - Physics Hooks via engine allocators

---

## Advanced Game Engine Reverse Engineering

Deep dives into modern game engines with architecture-specific exploitation techniques.

### Source Engine 2 (CS2, Dota 2)

Source 2 uses a completely redesigned architecture with schema system for entity reflection.

#### Schema System Exploitation

```cpp
// Source 2 schema provides runtime type information
class CSchemaSystem {
public:
    CSchemaClassInfo* FindTypeScopeForModule(const char* moduleName);
};

// Dump all entity classes
void DumpSchemaSystem() {
    CSchemaSystem* pSchemaSystem = GetSchemaSystem();
    
    CSchemaClassInfo* pClassInfo = pSchemaSystem->FindTypeScopeForModule("client.dll");
    
    for (auto& classBinding : pClassInfo->m_ClassBindings) {
        printf("Class: %s\n", classBinding.m_pClassName);
        
        // Enumerate fields
        for (int i = 0; i < classBinding.m_nFieldCount; i++) {
            SchemaClassFieldData_t& field = classBinding.m_pFields[i];
            printf("  Field: %s (Offset: 0x%X, Type: %s)\n", 
                   field.m_pName, field.m_nOffset, field.m_pType->m_pTypeName);
        }
    }
}
```

#### Networked Entity Hooking

```cpp
// Hook entity creation to monitor players
typedef void* (*CreateNetworkEntity_t)(const char* className, int index);
CreateNetworkEntity_t oCreateNetworkEntity;

void* hkCreateNetworkEntity(const char* className, int index) {
    void* entity = oCreateNetworkEntity(className, index);
    
    if (strcmp(className, "CCSPlayerPawn") == 0) {
        // Track player entity
        g_PlayerEntities[index] = entity;
        
        // Read position offset from schema
        int posOffset = GetSchemaOffset("CCSPlayerPawn", "m_vPosition");
        Vector* pPos = (Vector*)((uintptr_t)entity + posOffset);
        
        printf("Player spawned at: %.2f, %.2f, %.2f\n", pPos->x, pPos->y, pPos->z);
    }
    
    return entity;
}
```

#### Panorama UI Injection

```cpp
// Source 2 uses Panorama (HTML/CSS/JS UI)
void InjectPanoramaPanel() {
    IPanoramaUIEngine* pPanorama = GetPanoramaUIEngine();
    
    // Create custom panel
    const char* panelXML = R"(
        <Panel class="ESP-Panel">
            <Label id="player-info" text="ESP Active" />
            <Canvas id="esp-canvas" />
        </Panel>
    )";
    
    IUIPanel* pPanel = pPanorama->CreatePanel(panelXML, "HudRoot");
    
    // Hook JavaScript context
    v8::Isolate* isolate = pPanorama->GetV8Isolate();
    v8::HandleScope scope(isolate);
    
    // Inject ESP rendering function
    const char* espScript = R"(
        $.RegisterForUnhandledEvent('PaintWorld', function() {
            var players = GameStateAPI.GetPlayerPositions();
            players.forEach(function(player) {
                DrawESPBox(player.x, player.y, player.z);
            });
        });
    )";
    
    pPanorama->ExecuteScript(espScript);
}
```

---

### Frostbite Engine (Battlefield, FIFA)

Frostbite uses an entity-component system with a complex reflection architecture.

#### Entity Bus Traversal

```cpp
// Frostbite uses EntityBus for entity management
class ClientGameContext {
public:
    ClientPlayerManager* m_pPlayerManager;  // +0x30
};

class ClientPlayerManager {
public:
    ClientPlayer** m_ppPlayers;  // +0x98
    uint32_t m_playerCount;      // +0xA0
};

class ClientPlayer {
public:
    ClientSoldierEntity* m_pSoldier;  // +0x1478 (BF2042)
    char m_name[16];                   // +0x40
};

void DumpAllPlayers() {
    ClientGameContext* pGameContext = *(ClientGameContext**)GetGameContextPtr();
    ClientPlayerManager* pPlayerMgr = pGameContext->m_pPlayerManager;
    
    for (uint32_t i = 0; i < pPlayerMgr->m_playerCount; i++) {
        ClientPlayer* pPlayer = pPlayerMgr->m_ppPlayers[i];
        
        if (pPlayer && pPlayer->m_pSoldier) {
            LinearTransform* pTransform = pPlayer->m_pSoldier->GetTransform();
            Vector3 pos = pTransform->GetPosition();
            
            printf("Player: %s at (%.2f, %.2f, %.2f)\n", 
                   pPlayer->m_name, pos.x, pos.y, pos.z);
        }
    }
}
```

#### TypeInfo Reflection

```cpp
// Frostbite TypeInfo system provides RTTI
class TypeInfo {
public:
    const char* m_pName;
    TypeInfo* m_pParent;
    uint16_t m_fieldCount;
    FieldInfo* m_pFields;
};

class FieldInfo {
public:
    const char* m_pName;
    uint16_t m_offset;
    TypeInfo* m_pTypeInfo;
};

// Automatically resolve offsets
int FindOffset(const char* className, const char* fieldName) {
    TypeInfo* pTypeInfo = FindTypeInfo(className);
    
    for (int i = 0; i < pTypeInfo->m_fieldCount; i++) {
        if (strcmp(pTypeInfo->m_pFields[i].m_pName, fieldName) == 0) {
            return pTypeInfo->m_pFields[i].m_offset;
        }
    }
    
    return -1;
}
```

#### Havok Physics Manipulation

```cpp
// Frostbite uses Havok for physics
class hkpWorld {
public:
    hkArray<hkpRigidBody*> m_rigidBodies;
};

void DisableGravity() {
    hkpWorld* pPhysicsWorld = GetHavokWorld();
    
    for (int i = 0; i < pPhysicsWorld->m_rigidBodies.getSize(); i++) {
        hkpRigidBody* pBody = pPhysicsWorld->m_rigidBodies[i];
        
        // Set gravity factor to 0
        pBody->setGravityFactor(0.0f);
        
        // Or directly modify velocity
        hkVector4 zero; zero.setZero();
        pBody->setLinearVelocity(zero);
    }
}
```

---

### REDengine (Cyberpunk 2077)

REDengine uses RTTI with extensive script integration.

#### RTTI Dump

```cpp
// REDengine RTTI structure
struct CClass {
    const char* name;
    CClass* parent;
    CProperty** properties;
    uint32_t propertyCount;
    CFunction** functions;
    uint32_t functionCount;
};

struct CProperty {
    const char* name;
    CName type;
    uint32_t offset;
    uint32_t flags;
};

void DumpRTTI() {
    CRTTISystem* rtti = CRTTISystem::Get();
    
    // Iterate all classes
    for (auto& kv : rtti->m_classes) {
        CClass* pClass = kv.second;
        
        printf("Class: %s (Parent: %s)\n", 
               pClass->name, 
               pClass->parent ? pClass->parent->name : "None");
        
        // Dump properties
        for (uint32_t i = 0; i < pClass->propertyCount; i++) {
            CProperty* prop = pClass->properties[i];
            printf("  +0x%X: %s %s\n", prop->offset, prop->type.ToString(), prop->name);
        }
    }
}
```

#### Script Hook Injection

```cpp
// Hook RED4ext scripting system
using ScriptFunction_t = void (*)(IScriptable*, CStackFrame*, void*, void*);

void HookScriptFunction(const char* className, const char* funcName, ScriptFunction_t hook) {
    CClass* pClass = CRTTISystem::Get()->GetClass(CName(className));
    CFunction* pFunc = pClass->GetFunction(CName(funcName));
    
    // Replace function pointer
    *(ScriptFunction_t*)&pFunc->m_pFunc = hook;
}

// Hook player damage
void Hook_ApplyDamage(IScriptable* context, CStackFrame* frame, void* ret, void* unk) {
    float damage;
    frame->GetParameter(&damage);
    
    // Nullify damage (god mode)
    damage = 0.0f;
    
    // Continue with modified parameter
    Original_ApplyDamage(context, frame, &damage, unk);
}
```

#### Save Game Modification

```cpp
// Cyberpunk save structure
struct SaveHeader {
    uint32_t magic;  // 'SAVE'
    uint32_t version;
    uint64_t timestamp;
    char characterName[64];
};

struct InventoryItem {
    uint64_t itemId;
    uint32_t quantity;
    uint32_t quality;  // 0=Common, 1=Uncommon, 2=Rare, 3=Epic, 4=Legendary
};

void ModifySave(const char* savePath) {
    std::ifstream file(savePath, std::ios::binary);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(file)), 
                               std::istreambuf_iterator<char>());
    
    // Find inventory section (signature scan)
    const uint8_t inventoryMarker[] = {0x49, 0x4E, 0x56, 0x54};  // "INVT"
    auto it = std::search(data.begin(), data.end(), 
                          std::begin(inventoryMarker), std::end(inventoryMarker));
    
    if (it != data.end()) {
        size_t offset = std::distance(data.begin(), it) + 4;
        
        // Modify all items to legendary
        for (size_t i = offset; i < data.size() - sizeof(InventoryItem); i += sizeof(InventoryItem)) {
            InventoryItem* item = (InventoryItem*)&data[i];
            if (item->itemId != 0) {
                item->quality = 4;  // Legendary
                item->quantity = 999;
            }
        }
    }
    
    // Write modified save
    std::ofstream outFile(savePath, std::ios::binary);
    outFile.write((char*)data.data(), data.size());
}
```

---

### id Tech (Doom Eternal, Quake Champions)

id Tech engines are known for their performance and moddability.

#### Entity System Reversing

```cpp
// id Tech entity structure
struct idEntity {
    int entityNumber;
    int entityDefNumber;
    const char* name;
    idVec3 origin;
    idMat3 axis;
    int health;
    int maxHealth;
};

// Game uses entity dictionary
class idGameLocal {
public:
    idEntity* entities[MAX_GENTITIES];
    int numEntities;
};

void ScanEntities() {
    idGameLocal* game = GetGameLocal();
    
    for (int i = 0; i < game->numEntities; i++) {
        idEntity* ent = game->entities[i];
        
        if (ent && ent->health > 0) {
            printf("Entity %d: %s at (%.2f, %.2f, %.2f) HP: %d/%d\n",
                   ent->entityNumber,
                   ent->name,
                   ent->origin.x, ent->origin.y, ent->origin.z,
                   ent->health, ent->maxHealth);
        }
    }
}
```

#### Console Command Injection

```cpp
// id Tech console system
class idCmdSystem {
public:
    virtual void ExecuteCommand(const char* cmd) = 0;
    virtual void AddCommand(const char* name, void (*func)(const idCmdArgs&)) = 0;
};

void RegisterCheatCommands() {
    idCmdSystem* cmdSystem = GetCmdSystem();
    
    // Add god mode command
    cmdSystem->AddCommand("god", [](const idCmdArgs& args) {
        idPlayer* player = GetLocalPlayer();
        player->godmode = !player->godmode;
        printf("God mode: %s\n", player->godmode ? "ON" : "OFF");
    });
    
    // Add noclip
    cmdSystem->AddCommand("noclip", [](const idCmdArgs& args) {
        idPlayer* player = GetLocalPlayer();
        player->noclip = !player->noclip;
        player->physicsObj.SetClipModel(nullptr);
    });
    
    // Give all weapons
    cmdSystem->AddCommand("give all", [](const idCmdArgs& args) {
        idPlayer* player = GetLocalPlayer();
        for (int i = 0; i < WEAPON_COUNT; i++) {
            player->GiveWeapon(i);
        }
    });
}
```

#### .resources File Manipulation

```cpp
// id Tech 7 uses .resources container format
struct ResourceHeader {
    uint64_t magic;  // 'idResource'
    uint32_t version;
    uint32_t fileCount;
    uint64_t stringsOffset;
    uint64_t dataOffset;
};

struct ResourceEntry {
    uint64_t nameHash;
    uint64_t offset;
    uint64_t compressedSize;
    uint64_t uncompressedSize;
    uint32_t compressionType;  // 0=None, 1=Oodle, 2=Zlib
};

void ExtractResources(const char* resourcePath) {
    std::ifstream file(resourcePath, std::ios::binary);
    
    ResourceHeader header;
    file.read((char*)&header, sizeof(header));
    
    for (uint32_t i = 0; i < header.fileCount; i++) {
        ResourceEntry entry;
        file.read((char*)&entry, sizeof(entry));
        
        // Read compressed data
        std::vector<uint8_t> compressed(entry.compressedSize);
        file.seekg(entry.offset);
        file.read((char*)compressed.data(), entry.compressedSize);
        
        // Decompress (Oodle)
        std::vector<uint8_t> decompressed(entry.uncompressedSize);
        OodleLZ_Decompress(compressed.data(), entry.compressedSize,
                          decompressed.data(), entry.uncompressedSize);
        
        // Save file
        char filename[256];
        sprintf(filename, "extracted/%016llX.bin", entry.nameHash);
        std::ofstream out(filename, std::ios::binary);
        out.write((char*)decompressed.data(), entry.uncompressedSize);
    }
}
```

---

### Godot 4 Deep Dive

Godot is open-source, making reverse engineering easier but still requires understanding its architecture.

#### GDScript Bytecode Modification

```python

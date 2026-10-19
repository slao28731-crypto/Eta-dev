## Mobile Game Hacking (Android and iOS)

Explore the offensive security techniques and reverse engineering approaches used to dissect, modify, and automate mobile games. This section covers app decompilation, runtime instrumentation, anti-cheat bypassing, and automation using modern tools like Frida, Magisk, APKTool, and more.

**Primary focus:** Android (APK) and iOS (IPA) game hacking for educational, red teaming, and CTF purposes only.

---

### Overview

| Platform | Technique                  | Tools                            |
|----------|----------------------------|----------------------------------|
| Android  | APK reverse engineering    | APKTool, jadx, Ghidra            |
| Android  | Runtime hooking            | Frida, Magisk, ptrace            |
| iOS      | Jailbreak + class dumping  | Frida, Hopper, LLDB              |
| All      | Input automation & bots    | AutoTouch, ADB, Appium           |
| All      | Anti-cheat bypassing       | Root/Jailbreak detection evasion |

---

### APK Reverse Engineering (Android)

#### APK Decompilation (Beginner)

**Tools Required**
- apktool
- jadx
- Java Decompiler
- dex2jar

**Workflow**
```bash
apktool d mygame.apk -o mygame_dec/
jadx mygame.apk  # GUI decompiler
```

Explore `smali/` files or Java classes:

Look for `onPurchase()`, `checkGold()`, `inventoryManager`, etc.

Patch logic like:
```smali
invoke-static {v0}, Lcom/game/store/CheckPurchase;->isAllowed()Z
move-result v1
if-eqz v1, :original_code

const/4 v1, 0x1   # Always allow
```

---

### Smali Modification (Intermediate)

Patch APK logic via smali edits:
```smali
.method public isRooted()Z
    .registers 2
    const/4 v0, 0x0  # Force "not rooted"
    return v0
.end method
```

Rebuild & resign:
```bash
apktool b mygame_dec/ -o modded.apk
jarsigner -keystore my-release-key.keystore modded.apk alias_name
adb install -r modded.apk
```

---

### Frida for Android and iOS (Dynamic Instrumentation)

### Setup (Android)

- Rooted or Magisk-enabled phone
- Install frida-server matching phone architecture

Push and run:
```bash
adb push frida-server /data/local/tmp/
adb shell "chmod 755 /data/local/tmp/frida-server && ./data/local/tmp/frida-server &"
```

On host:
```bash
frida -U -n com.example.game
```

---

### Example: Hooking Currency Function

```js
Java.perform(function() {
    var GameUtils = Java.use("com.example.game.CurrencyManager");
    GameUtils.getCoins.implementation = function() {
        console.log("[+] Hooked getCoins!");
        return 999999;
    };
});
```

Hot reloadable without repackaging the APK.

---

### Frida on iOS (Advanced)

- Jailbreak device with Checkra1n or TrollStore-compatible firmware
- Install frida via Cydia or Sileo

Attach to process:
```bash
frida -U -n MyGame
```

Hook Objective-C methods:
```js
ObjC.schedule(ObjC.mainQueue, function() {
    var cls = ObjC.classes.InAppPurchaseManager;
    var sel = 'checkTransaction:';
    Interceptor.attach(cls[sel].implementation, {
        onEnter: function(args) {
            console.log("[*] Intercepted in-app purchase:", ObjC.Object(args[2]));
        }
    });
});
```

---

### Android Root Detection Bypass

Common detection flags:
- `Build.TAGS` contains `test-keys`
- `su` binary in `/system/bin/`
- Magisk modules
- Access to `frida-server`

**Frida Hook Example: Disable Root Checks**
```js
Java.perform(function () {
    var RootCheck = Java.use("com.example.anticheat.Checks");
    RootCheck.isDeviceRooted.implementation = function () {
        return false;
    };
});
```

**Magisk Hide + Zygisk Modules**
- Use MagiskHidePropsConf to spoof build fingerprint
- Use Zygisk + Shamiko to hide root from Zygote-initialized apps

---

### iOS Jailbreak Detection Bypass

Typical Checks:
- `fileExistsAtPath("/Applications/Cydia.app")`
- `canOpenURL("cydia://")`
- `fork()`, `getppid()`, `sysctl`

**Frida Hook (iOS)**
```js
Interceptor.attach(Module.findExportByName(null, "stat"), {
  onEnter(args) {
    var path = Memory.readUtf8String(args[0]);
    if (path.indexOf("Cydia") !== -1) {
      Memory.writeUtf8String(args[0], "/fakepath");
    }
  }
});
```

---

### Mobile Input Automation and Bots

### Android Automation

**Tools:**
- ADB + scrcpy + Python
- AutoInput + Tasker
- MonkeyRunner
- uiautomator

**Example: Tap Resource Nodes with Python + ADB**
```python
import os, time
while True:
    os.system("adb shell input tap 540 1200")
    time.sleep(1.5)
```

---

### iOS Automation (Jailbreak Required)

**Tools:**
- AutoTouch / TouchRecorder
- XCUITest (requires dev access)
- lldb input spoofing

---

### Advanced Tactics

| Technique             | Description                               | Platform |
|-----------------------|-------------------------------------------|----------|
| Inline Native Hooking | Hook `libil2cpp.so`, `libunity.so`        | Android  |
| Class Dumping         | Dump all classes from ObjC runtime        | iOS      |
| Patch In-Memory Data  | Use `Frida.Memory.write*()` for RAM edits | All      |
| Runtime Memory Scanning | Use Frida to find health/coin vars      | Android  |
| Emulator Bypass       | Patch `ro.hardware` and sensors           | Android  |

---

### Anti-AntiCheat and Evasion

| Detection Type   | Evasion Technique                          |
|------------------|--------------------------------------------|
| Magisk detection | Use Zygisk + Shamiko                       |
| Root binaries    | Rename `su`, hide mounts                   |
| Debugger attach  | Patch `ptrace()` via Frida                 |
| Frida detection  | Rename `frida-server`, patch symbol calls  |
| Jailbreak (iOS)  | Use libhooker, patch `fileExistsAtPath()`  |
---
## Cross-Platform & Emulation Exploitation

### Proton/Wine Game Memory Access

```bash
## Advanced Mobile Game Exploitation

### iOS Advanced Techniques

#### Jailbreak Detection Bypass

```objc
// Hook jailbreak detection methods
%hook JailbreakDetector

- (BOOL)isJailbroken {
    return NO;
}

- (BOOL)checkForCydia {
    return NO;
}

- (BOOL)checkSuspiciousFiles {
    return NO;
}

%end

// Hook file access to hide jailbreak files
%hook NSFileManager

- (BOOL)fileExistsAtPath:(NSString *)path {
    NSArray *blacklist = @[@"/Applications/Cydia.app",
                           @"/Library/MobileSubstrate",
                           @"/bin/bash",
                           @"/usr/sbin/sshd"];
    
    if ([blacklist containsObject:path]) {
        return NO;
    }
    
    return %orig;
}

%end
```

#### iOS Memory Manipulation with Frida

```javascript
// Attach to iOS game
const baseAddr = Module.findBaseAddress('GameBinary');

// Find and patch currency
const currencyPattern = '48 8B 05 ?? ?? ?? ?? 48 8B 48 10';
const matches = Memory.scanSync(baseAddr, Process.pageSize * 1000, currencyPattern);

matches.forEach(match => {
    console.log('[+] Found currency at:', match.address);
    
    // Patch to always return max value
    Memory.protect(match.address, 16, 'rwx');
    match.address.writeByteArray([
        0xB8, 0xFF, 0xFF, 0xFF, 0x7F,  // mov eax, 0x7FFFFFFF
        0xC3                            // ret
    ]);
});
```

#### iOS SSL Pinning Bypass

```javascript
// Bypass SSL pinning
if (ObjC.available) {
    // Bypass NSURLSession pinning
    const NSURLSession = ObjC.classes.NSURLSession;
    Interceptor.attach(NSURLSession['- URLSession:didReceiveChallenge:completionHandler:'].implementation, {
        onEnter: function(args) {
            const completionHandler = new ObjC.Block(args[4]);
            const origImpl = completionHandler.implementation;
            
            completionHandler.implementation = function(disposition, credential) {
                // Accept any certificate
                return origImpl(1, credential);
            };
        }
    });
    
    // Bypass AFNetworking pinning
    const AFHTTPSessionManager = ObjC.classes.AFHTTPSessionManager;
    if (AFHTTPSessionManager) {
        Interceptor.replace(AFHTTPSessionManager['- setSecurityPolicy:'].implementation, new NativeCallback(function(self, sel, policy) {
            console.log('[+] Bypassed AFNetworking SSL pinning');
        }, 'void', ['pointer', 'pointer', 'pointer']));
    }
}
```

### Android Advanced Techniques

#### Root Detection Bypass (Native)

```c
// Hook native root checks with Substrate
#include <substrate.h>

static int (*original_access)(const char *pathname, int mode);

int hooked_access(const char *pathname, int mode) {
    // Block access to root indicators
    const char *root_files[] = {
        "/system/app/Superuser.apk",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/data/com.noshufou.android.su",
        "/data/data/com.topjohnwu.magisk"
    };
    
    for (int i = 0; i < sizeof(root_files)/sizeof(root_files[0]); i++) {
        if (strcmp(pathname, root_files[i]) == 0) {
            errno = ENOENT;
            return -1;
        }
    }
    
    return original_access(pathname, mode);
}

__attribute__((constructor))
static void initialize() {
    MSHookFunction((void *)access, (void *)hooked_access, (void **)&original_access);
}
```

#### Android Memory Scanning with /proc/maps

```python
import os
import struct

class AndroidMemoryScanner:
    def __init__(self, package_name):
        self.pid = self.get_pid(package_name)
        
    def get_pid(self, package_name):
        # Get PID from ps
        output = os.popen(f'adb shell "ps | grep {package_name}"').read()
        return int(output.split()[1])
    
    def scan_memory(self, value, value_type='i'):
        matches = []
        
        # Read memory maps
        maps = os.popen(f'adb shell "cat /proc/{self.pid}/maps"').read()
        
        for line in maps.split('\n'):
            if 'rw' not in line or '[' in line:
                continue
                
            parts = line.split()
            addr_range = parts[0].split('-')
            start = int(addr_range[0], 16)
            end = int(addr_range[1], 16)
            
            # Pull memory region
            size = end - start
            if size > 10000000:  # Skip huge regions
                continue
                
            try:
                mem_data = self.read_memory(start, size)
                matches.extend(self.find_value(mem_data, value, start, value_type))
            except:
                pass
        
        return matches
    
    def read_memory(self, address, size):
        cmd = f'adb shell "su -c \'dd if=/proc/{self.pid}/mem bs=1 count={size} skip={address} 2>/dev/null\'" | xxd -p'
        hex_data = os.popen(cmd).read().replace('\n', '')
        return bytes.fromhex(hex_data)
    
    def find_value(self, data, value, base_addr, value_type):
        matches = []
        packed = struct.pack(value_type, value)
        
        offset = 0
        while True:
            offset = data.find(packed, offset)
            if offset == -1:
                break
            matches.append(base_addr + offset)
            offset += 1
        
        return matches

## Cloud Save & Achievement Exploitation

### Cloud Save Tampering

#### Steam Cloud Save Manipulation

```python
import os
import vdf  # Valve Data Format parser
import struct

class SteamCloudExploit:
    def __init__(self, app_id):
        self.app_id = app_id
        self.steam_path = os.path.expanduser('~/.steam/steam')
        self.userdata_path = os.path.join(self.steam_path, 'userdata')
        
    def find_save_files(self):
        saves = []
        for user_id in os.listdir(self.userdata_path):
            save_dir = os.path.join(self.userdata_path, user_id, str(self.app_id), 'remote')
            if os.path.exists(save_dir):
                for file in os.listdir(save_dir):
                    saves.append(os.path.join(save_dir, file))
        return saves
    
    def modify_save(self, save_path, modifications):
        with open(save_path, 'rb') as f:
            data = bytearray(f.read())
        
        # Example: Modify currency at known offset
        if 'currency' in modifications:
            offset = modifications['currency']['offset']
            value = modifications['currency']['value']
            struct.pack_into('<I', data, offset, value)
        
        # Recalculate checksum if present
        if self.has_checksum(data):
            checksum = self.calculate_checksum(data[:-4])
            struct.pack_into('<I', data, len(data) - 4, checksum)
        
        with open(save_path, 'wb') as f:
            f.write(data)
    
    def calculate_checksum(self, data):
        # CRC32 checksum
        import zlib
        return zlib.crc32(data) & 0xFFFFFFFF

## Speedrunning Tools & Techniques

### Tool-Assisted Speedrun (TAS) Framework

```python
import time
import keyboard
import json

class TASFramework:
    def __init__(self, game_fps=60):
        self.game_fps = game_fps
        self.frame_time = 1.0 / game_fps
        self.inputs = []
        self.recording = False
        
    def record_frame(self):
        frame_inputs = {
            'keys': [],
            'mouse': {'x': 0, 'y': 0, 'buttons': []}
        }
        
        # Capture keyboard state
        for key_code in range(256):
            if keyboard.is_pressed(key_code):
                frame_inputs['keys'].append(key_code)
        
        return frame_inputs
    
    def start_recording(self):
        self.recording = True
        self.inputs = []
        
        print('[+] Recording TAS inputs...')
        
        while self.recording:
            frame_start = time.time()
            
            frame_data = self.record_frame()
            self.inputs.append(frame_data)
            
            # Wait for next frame
            elapsed = time.time() - frame_start
            if elapsed < self.frame_time:
                time.sleep(self.frame_time - elapsed)
    
    def stop_recording(self):
        self.recording = False
        print(f'[+] Recorded {len(self.inputs)} frames')
    
    def playback(self):
        print('[+] Playing back TAS...')
        
        for i, frame in enumerate(self.inputs):
            frame_start = time.time()
            
            # Release all keys first
            keyboard.unhook_all()
            
            # Press frame keys
            for key in frame['keys']:
                keyboard.press(key)
            
            # Wait for next frame
            elapsed = time.time() - frame_start
            if elapsed < self.frame_time:
                time.sleep(self.frame_time - elapsed)
            
            # Release keys
            for key in frame['keys']:
                keyboard.release(key)
    
    def save(self, filename):
        with open(filename, 'w') as f:
            json.dump(self.inputs, f)
    
    def load(self, filename):
        with open(filename, 'r') as f:
            self.inputs = json.load(f)


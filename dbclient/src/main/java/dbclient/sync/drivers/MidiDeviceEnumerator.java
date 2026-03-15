package dbclient.sync.drivers;

import javax.sound.midi.*;
import java.util.*;

/**
 * MidiDeviceEnumerator - MIDI 设备枚举器
 * 
 * 枚举所有可用的 MIDI OUT 端口，包括：
 * - 物理 MIDI 接口
 * - 虚拟 MIDI 端口
 * - Loopback / Bridge 端口
 */
public class MidiDeviceEnumerator {
    
    /**
     * MIDI 端口信息
     */
    public static class MidiPort {
        public final String id;           // 设备标识
        public final String name;         // 设备名称
        public final String description;  // 描述
        public final boolean isVirtual;   // 是否为虚拟设备
        
        public MidiPort(String id, String name, String description, boolean isVirtual) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.isVirtual = isVirtual;
        }
    }
    
    /**
     * 枚举所有可用的 MIDI OUT 端口
     */
    public static List<MidiPort> enumerateOutputPorts() {
        List<MidiPort> ports = new ArrayList<>();
        
        System.out.println("=== MIDI OUT Ports Enumeration ===");
        
        try {
            MidiDevice.Info[] deviceInfos = MidiSystem.getMidiDeviceInfo();
            
            for (MidiDevice.Info info : deviceInfos) {
                try {
                    MidiDevice device = MidiSystem.getMidiDevice(info);
                    
                    // 关键：检查是否有输出能力（Receiver）
                    // maxReceivers != 0 表示可以作为 MIDI OUT
                    int maxReceivers = device.getMaxReceivers();
                    if (maxReceivers == 0) {
                        continue;  // 跳过纯输入设备
                    }
                    
                    // 判断是否为虚拟设备
                    boolean isVirtual = isVirtualPort(info);
                    
                    String id = info.getName();
                    String name = info.getName();
                    String description = info.getDescription() + " / " + info.getVendor();
                    
                    MidiPort port = new MidiPort(id, name, description, isVirtual);
                    ports.add(port);
                    
                    String type = isVirtual ? "[virtual]" : "[physical]";
                    System.out.println("MIDI OUT: " + type + " " + name);
                    System.out.println("  Description: " + description);
                    System.out.println("  MaxReceivers: " + (maxReceivers == -1 ? "unlimited" : maxReceivers));
                    
                } catch (Exception e) {
                    System.out.println("MIDI: Error checking device " + info.getName() + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("MIDI Enumeration Error: " + e.getMessage());
        }
        
        // 如果没有找到端口，添加默认选项
        if (ports.isEmpty()) {
            ports.add(new MidiPort("default", "Default", "System Default MIDI", false));
        }
        
        System.out.println("Total MIDI OUT Ports: " + ports.size());
        System.out.println("================================\n");
        
        return ports;
    }
    
    /**
     * 判断是否为虚拟端口
     * 
     * 启发式判断，基于名称关键词
     */
    private static boolean isVirtualPort(MidiDevice.Info info) {
        String nameLower = info.getName().toLowerCase();
        String descLower = info.getDescription().toLowerCase();
        
        // 虚拟设备关键词
        String[] virtualKeywords = {
            "virtual", "loopbe", "loopmidi", "bome", 
            "rtp", "network", "wireless", "bridge",
            "internal", "microsoft", "gs wavetable",
            "synth", "synthesizer"
        };
        
        for (String keyword : virtualKeywords) {
            if (nameLower.contains(keyword) || descLower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取默认端口
     */
    public static MidiPort getDefaultPort() {
        List<MidiPort> ports = enumerateOutputPorts();
        if (!ports.isEmpty()) {
            return ports.get(0);
        }
        return new MidiPort("default", "Default", "System Default MIDI", false);
    }
}

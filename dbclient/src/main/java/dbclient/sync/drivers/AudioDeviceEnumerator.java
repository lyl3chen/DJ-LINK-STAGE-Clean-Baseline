package dbclient.sync.drivers;

import javax.sound.sampled.*;
import java.util.*;

/**
 * AudioDeviceEnumerator - 音频设备枚举器
 * 
 * 枚举所有可用的音频输出设备，包括：
 * - 物理声卡
 * - 虚拟声卡 / 虚拟音频线
 * - Loopback / Bridge 设备
 */
public class AudioDeviceEnumerator {
    
    /**
     * 音频设备信息
     */
    public static class AudioDevice {
        public final String id;           // 设备标识
        public final String name;         // 设备名称
        public final String description;  // 描述
        public final boolean isVirtual;   // 是否为虚拟设备
        
        public AudioDevice(String id, String name, String description, boolean isVirtual) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.isVirtual = isVirtual;
        }
    }
    
    /**
     * 枚举所有可用的音频输出设备
     */
    public static List<AudioDevice> enumerateOutputDevices() {
        List<AudioDevice> devices = new ArrayList<>();
        
        System.out.println("=== Audio Devices Enumeration ===");
        
        try {
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            
            for (Mixer.Info info : mixerInfos) {
                try {
                    Mixer mixer = AudioSystem.getMixer(info);
                    
                    // 检查是否有输出能力（SourceLine）
                    Line.Info[] sourceLineInfos = mixer.getSourceLineInfo();
                    if (sourceLineInfos.length == 0) {
                        continue;  // 跳过纯输入设备
                    }
                    
                    // 测试是否能打开 SourceDataLine（关键：LTC 输出需要）
                    boolean canOutput = false;
                    for (Line.Info lineInfo : sourceLineInfos) {
                        if (lineInfo instanceof DataLine.Info) {
                            DataLine.Info dataLineInfo = (DataLine.Info) lineInfo;
                            if (SourceDataLine.class.isAssignableFrom(dataLineInfo.getLineClass())) {
                                try {
                                    // 尝试打开验证
                                    SourceDataLine testLine = (SourceDataLine) mixer.getLine(lineInfo);
                                    // 不真正打开，只是验证可行性
                                    canOutput = true;
                                    break;
                                } catch (Exception e) {
                                    // 无法打开，跳过
                                }
                            }
                        }
                    }
                    
                    if (!canOutput) {
                        continue;
                    }
                    
                    // 判断是否为虚拟设备
                    boolean isVirtual = isVirtualDevice(info);
                    
                    String id = info.getName();
                    String name = info.getName();
                    String description = info.getDescription() + " / " + info.getVendor();
                    
                    AudioDevice device = new AudioDevice(id, name, description, isVirtual);
                    devices.add(device);
                    
                    String type = isVirtual ? "[virtual]" : "[physical]";
                    System.out.println("Audio Output: " + type + " " + name);
                    System.out.println("  Description: " + description);
                    System.out.println("  SourceLines: " + sourceLineInfos.length);
                    
                } catch (Exception e) {
                    System.out.println("Audio: Error checking mixer " + info.getName() + ": " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("Audio Enumeration Error: " + e.getMessage());
        }
        
        // 如果没有找到设备，添加默认选项
        if (devices.isEmpty()) {
            devices.add(new AudioDevice("default", "Default", "System Default Audio", false));
        }
        
        System.out.println("Total Audio Output Devices: " + devices.size());
        System.out.println("================================\n");
        
        return devices;
    }
    
    /**
     * 判断是否为虚拟设备
     * 
     * 启发式判断，基于名称关键词
     */
    private static boolean isVirtualDevice(Mixer.Info info) {
        String nameLower = info.getName().toLowerCase();
        String descLower = info.getDescription().toLowerCase();
        
        // 虚拟设备关键词
        String[] virtualKeywords = {
            "virtual", "vb-", "voicemeeter", "cable", 
            "loopback", "blackhole", "soundflower",
            "stereomix", "what u hear", "mix", "bridge",
            "scream", "jamulus", "asio4all", "flexasio"
        };
        
        for (String keyword : virtualKeywords) {
            if (nameLower.contains(keyword) || descLower.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 获取默认设备
     */
    public static AudioDevice getDefaultDevice() {
        try {
            Mixer.Info defaultInfo = AudioSystem.getMixerInfo()[0];
            return new AudioDevice(
                "default",
                "Default",
                "System Default",
                false
            );
        } catch (Exception e) {
            return new AudioDevice("default", "Default", "System Default", false);
        }
    }

    /**
     * 根据设备名称查找 Mixer
     * @param deviceName 设备名称（匹配 mixerInfo.getName()）
     * @return 找到的 Mixer，或 null 如果未找到
     */
    public static Mixer findMixerByName(String deviceName) {
        if (deviceName == null || "default".equals(deviceName)) {
            return null; // 使用默认
        }

        try {
            Mixer.Info[] mixerInfos = AudioSystem.getMixerInfo();
            for (Mixer.Info info : mixerInfos) {
                if (info.getName().equals(deviceName)) {
                    return AudioSystem.getMixer(info);
                }
            }
        } catch (Exception e) {
            System.err.println("[AudioDeviceEnumerator] Error finding mixer: " + e.getMessage());
        }
        return null; // 未找到
    }

    /**
     * 获取指定设备的 SourceDataLine
     * @param deviceName 设备名称（或 "default"）
     * @param format 音频格式
     * @return SourceDataLine
     * @throws Exception 如果设备不存在或无法打开
     */
    public static SourceDataLine getSourceDataLine(String deviceName, AudioFormat format) throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        if (deviceName == null || "default".equals(deviceName)) {
            // 使用系统默认
            return (SourceDataLine) AudioSystem.getLine(info);
        }

        // 按名称查找设备
        Mixer mixer = findMixerByName(deviceName);
        if (mixer == null) {
            throw new Exception("Audio device not found: " + deviceName);
        }

        // 从指定 mixer 获取 line
        try {
            return (SourceDataLine) mixer.getLine(info);
        } catch (Exception e) {
            throw new Exception("Cannot open audio device '" + deviceName + "': " + e.getMessage());
        }
    }
}

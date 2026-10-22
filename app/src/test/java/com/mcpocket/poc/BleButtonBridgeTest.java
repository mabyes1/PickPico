package com.mcpocket.poc;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@LooperMode(LooperMode.Mode.PAUSED)
public class BleButtonBridgeTest {
    @Test public void unusedPadNeverStartsBluetoothSearch() throws Exception {
        McpNodeService service = Robolectric.buildService(McpNodeService.class).get();
        BleButtonBridge bridge = new BleButtonBridge(service);
        bridge.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5));
        assertFalse(bridge.status().getBoolean("enabled"));
        assertFalse(bridge.status().getBoolean("searchActive"));
        assertEquals(0, bridge.status().getInt("scanAttempts"));
    }

    @Test public void searchIsLowPowerBoundedAndCanBeStopped() throws Exception {
        McpNodeService service = spy(Robolectric.buildService(McpNodeService.class).get());
        BluetoothManager manager = mock(BluetoothManager.class);
        BluetoothAdapter adapter = mock(BluetoothAdapter.class);
        BluetoothLeScanner scanner = mock(BluetoothLeScanner.class);
        doReturn(manager).when(service).getSystemService(BluetoothManager.class);
        doReturn(PackageManager.PERMISSION_GRANTED).when(service).checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN);
        doReturn(PackageManager.PERMISSION_GRANTED).when(service).checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT);
        when(manager.getAdapter()).thenReturn(adapter);
        when(adapter.isEnabled()).thenReturn(true);
        when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
        McpocketPolicySettings.setButtonPadEnabled(service, true);
        BleButtonBridge bridge = new BleButtonBridge(service);
        bridge.start();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5));
        ArgumentCaptor<ScanSettings> settings = ArgumentCaptor.forClass(ScanSettings.class);
        verify(scanner, times(3)).startScan(anyList(), settings.capture(), any(ScanCallback.class));
        for (ScanSettings scan : settings.getAllValues()) assertEquals(ScanSettings.SCAN_MODE_LOW_POWER, scan.getScanMode());
        assertFalse(bridge.status().getBoolean("searchActive"));
        assertFalse(bridge.status().getBoolean("scanning"));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30));
        verify(scanner, times(3)).startScan(anyList(), any(ScanSettings.class), any(ScanCallback.class));
        bridge.start();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(bridge.status().getBoolean("scanning"));
        bridge.stop();
        assertFalse(bridge.status().getBoolean("scanning"));
    }
}

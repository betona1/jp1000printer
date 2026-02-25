package android_serialport_api;

import java.io.InputStream;
import java.io.OutputStream;

// ------------------------------------------------------------------------
// Serial Communication  Control ( Use Lib Only )
// ------------------------------------------------------------------------
public interface IDevice {
	
	boolean connect();
	void disconnect();
	boolean isConnected();
	boolean isAvailable();
	
	InputStream getInputStream();
	OutputStream getOutputStream();
}

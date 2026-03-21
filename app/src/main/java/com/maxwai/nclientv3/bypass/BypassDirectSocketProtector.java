package com.maxwai.nclientv3.bypass;

import java.net.Socket;

public interface BypassDirectSocketProtector {
    boolean protectSocket(Socket socket);
}

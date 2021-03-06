package com.kano.spi;


import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Protocol;

public class KanoSpi {

    public static void main(String[] args) {
//        testProtocolAdaptive();
//        testProtocolDefault();
        testProtocolExtension();
    }

    public static void testProtocolAdaptive(){
        Protocol adaptiveExtension = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
        System.out.println("adaptive = " + adaptiveExtension);
    }

    public static void testProtocolDefault(){
        Protocol defaultExtension = ExtensionLoader.getExtensionLoader(Protocol.class).getDefaultExtension();
        System.out.println("default = " + defaultExtension);
    }


    public static void testProtocolExtension(){
        Protocol defaultExtension = ExtensionLoader.getExtensionLoader(Protocol.class).getExtension("dubbo");
        System.out.println("default = " + defaultExtension);
    }








}

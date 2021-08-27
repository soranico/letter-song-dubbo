package com.kano.spi;


import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Protocol;

public class KanoSpi {

    public static void main(String[] args) {
        testProtocolAdaptive();
    }

    public static void testProtocolAdaptive(){
        Protocol adaptiveExtension = ExtensionLoader.getExtensionLoader(Protocol.class).getAdaptiveExtension();
        System.out.println(adaptiveExtension);
    }




}

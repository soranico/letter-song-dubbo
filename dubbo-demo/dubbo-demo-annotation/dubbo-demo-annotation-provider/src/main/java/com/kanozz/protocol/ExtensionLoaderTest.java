package com.kanozz.protocol;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Protocol;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ExtensionLoaderTest {

    private static final Logger log = LoggerFactory.getLogger(ExtensionLoaderTest.class);

    @Test
    public void testDefaultProtocol(){
        ExtensionLoader<Protocol> protocolExtensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
        Protocol defaultExtension = protocolExtensionLoader.getDefaultExtension();
        log.info("extension = {}",defaultExtension);
    }

    @Test
    public void testProtocolExtension(){
        ExtensionLoader<Protocol> protocolExtensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
        Protocol defaultExtension = protocolExtensionLoader.getExtension("dubbo");
        log.info("extension = {}",defaultExtension);
    }


    @Test
    public void testProtocolExtensionWithoutWrapper(){
        ExtensionLoader<Protocol> protocolExtensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
        Protocol defaultExtension = protocolExtensionLoader.getExtension("dubbo",false);
        log.info("extension = {}",defaultExtension);
    }

    /**
     * 获取指定URL生效的 activate 类
     */
    @Test
    public void testProtocolActivate(){
        ExtensionLoader<Protocol> protocolExtensionLoader = ExtensionLoader.getExtensionLoader(Protocol.class);
        URL url = URL.valueOf("dubbo://172.17.32.91:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=dubbo-demo-api-provider&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=32508&release=&side=provider&timestamp=1593253404714dubbo://172.17.32.91:20880/org.apache.dubbo.demo.DemoService?anyhost=true&application=dubbo-demo-api-provider&dubbo=2.0.2&interface=org.apache.dubbo.demo.DemoService&methods=sayHello,sayHelloAsync&pid=32508&release=&side=provider&timestamp=1593253404714\n");
        List<Protocol> dubbo = protocolExtensionLoader.getActivateExtension(url, "dubbo");
        log.info("extension = {}",dubbo);
    }

}

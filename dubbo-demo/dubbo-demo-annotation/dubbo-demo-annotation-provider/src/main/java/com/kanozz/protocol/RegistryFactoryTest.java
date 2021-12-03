package com.kanozz.protocol;

import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.registry.RegistryFactory;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegistryFactoryTest {

    private static final Logger log = LoggerFactory.getLogger(RegistryFactoryTest.class);

    @Test
    public void testProtocolExtensionWithoutWrapper(){
        ExtensionLoader<RegistryFactory> protocolExtensionLoader = ExtensionLoader.getExtensionLoader(RegistryFactory.class);
        RegistryFactory defaultExtension = protocolExtensionLoader.getDefaultExtension();
        log.info("registryFactory = {}",defaultExtension);
    }
}

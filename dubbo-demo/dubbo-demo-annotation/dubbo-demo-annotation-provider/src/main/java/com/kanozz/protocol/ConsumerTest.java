package com.kanozz.protocol;

import com.kanozz.service.KanoService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.bootstrap.builders.ProtocolBuilder;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.junit.Test;

public class ConsumerTest {




    @Test
    public void consumerWithBootstrap(){

        ReferenceConfig<KanoService> reference = new ReferenceConfig<>();
        reference.setInterface(KanoService.class);
        reference.setCheck(false);

        ProtocolConfig protocolConfig = ProtocolBuilder.newBuilder().host("192.168.96.159")
            .port(9090).name("dubbo").build();

        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        bootstrap.application(new ApplicationConfig("kano-consumer"))
            .registry(new RegistryConfig("zookeeper://127.0.0.1:2181"))
            .protocol(protocolConfig)
            .reference(reference)
            .start();

        KanoService demoService = ReferenceConfigCache.getCache().get(reference);
        String message = demoService.kano();
        System.err.println(message);


    }



}

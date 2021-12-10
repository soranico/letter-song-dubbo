package com.kanozz.protocol;

import com.kanozz.service.KanoService;
import org.apache.dubbo.config.*;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.bootstrap.builders.ProtocolBuilder;
import org.apache.dubbo.config.utils.ReferenceConfigCache;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.Test;

import java.util.concurrent.Future;

public class ConsumerTest {




    @Test
    public void consumerWithBootstrap() throws Exception{

        ReferenceConfig<KanoService> reference = new ReferenceConfig<>();
        reference.setInterface(KanoService.class);
        reference.setCheck(false);
        MethodConfig methodConfig = new MethodConfig();
        methodConfig.setAsync(true);
        methodConfig.setName("kano");
        reference.addMethod(methodConfig);

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
        Future<Object> future = RpcContext.getServerContext().getFuture();
        System.err.println(message);

        System.in.read();


    }



}

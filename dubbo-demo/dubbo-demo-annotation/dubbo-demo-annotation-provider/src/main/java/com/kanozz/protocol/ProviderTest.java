package com.kanozz.protocol;

import com.kanozz.service.KanoProvider;
import com.kanozz.service.KanoService;
import com.kanozz.service.impl.KanoProviderImpl;
import com.kanozz.service.impl.KanoServiceImpl;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.config.bootstrap.builders.ProtocolBuilder;
import org.apache.dubbo.config.bootstrap.builders.RegistryBuilder;
import org.apache.dubbo.config.bootstrap.builders.ServiceBuilder;
import org.junit.Test;

import java.util.Collections;

public class ProviderTest {

    @Test
    public void testProviderWithExport() throws Exception {


        ApplicationConfig applicationConfig = new ApplicationConfig();
        applicationConfig.setName("kano");

        // 注册中心的连接信息
        RegistryConfig registryConfig = new RegistryConfig();
        registryConfig.setAddress("127.0.0.1");
        registryConfig.setProtocol("zookeeper");
        registryConfig.setPort(2181);

        ProtocolConfig protocolConfig = new ProtocolConfig();
        protocolConfig.setName("dubbo");

        // 服务的配置
        ServiceConfig<KanoService> serviceConfig = new ServiceConfig<>();
        serviceConfig.setRegistry(registryConfig);
        serviceConfig.setApplication(applicationConfig);
        serviceConfig.setProtocol(protocolConfig);
        serviceConfig.setInterface(KanoService.class);
        serviceConfig.setRef(new KanoServiceImpl());
        serviceConfig.setVersion("1.0");

//        serviceConfig.setMetadataReportConfig(new MetadataReportConfig("zookeeper://127.0.0.1:2181"));

        // 服务发布
        serviceConfig.export();

        System.in.read();

    }


    @Test
    public void testProviderWithBootstrap(){

        DubboBootstrap dubboBootstrap = DubboBootstrap.getInstance();
        dubboBootstrap.application("kano");
        // 注册地址
        RegistryConfig registryConfig = RegistryBuilder.newBuilder().protocol("zookeeper")
            .address("127.0.0.1").port(2181).build();
        dubboBootstrap.registry(registryConfig);
        // 使用协议
        ProtocolConfig protocolConfig = ProtocolBuilder.newBuilder().name("dubbo").build();
        dubboBootstrap.protocol(protocolConfig);


        ServiceConfig<Object> serviceConfig = ServiceBuilder.newBuilder().interfaceClass(KanoService.class)
            .ref(new KanoServiceImpl()).version("1.0").build();
        dubboBootstrap.services(Collections.singletonList(serviceConfig));
        dubboBootstrap.service(ServiceBuilder.newBuilder().interfaceClass(KanoProvider.class)
            .ref(new KanoProviderImpl()).version("1.0").build());
        dubboBootstrap.start().await();

    }




    @Test
    public void testStub(){
        DubboBootstrap dubboBootstrap = DubboBootstrap.getInstance();
        dubboBootstrap.application("kano");
        // 注册地址
        RegistryConfig registryConfig = RegistryBuilder.newBuilder().protocol("zookeeper")
            .address("127.0.0.1").port(2181).build();
        dubboBootstrap.registry(registryConfig);
        // 使用协议
        ProtocolConfig protocolConfig = ProtocolBuilder.newBuilder().name("dubbo").build();
        dubboBootstrap.protocol(protocolConfig);


        ServiceConfig<Object> serviceConfig = ServiceBuilder.newBuilder().interfaceClass(KanoService.class)
            .ref(new KanoServiceImpl()).version("1.0").stub(true).build();
        dubboBootstrap.services(Collections.singletonList(serviceConfig));
        dubboBootstrap.start().await();

    }


    @Test
    public  void startWithBootstrap() {
        ServiceConfig<KanoServiceImpl> service = new ServiceConfig<>();
        service.setInterface(KanoService.class);
        service.setRef(new KanoServiceImpl());

        DubboBootstrap bootstrap = DubboBootstrap.getInstance();
        bootstrap.application(new ApplicationConfig("kano-provider"))
            .registry(new RegistryConfig("zookeeper://127.0.0.1:2181"))
            .service(service)
            .start()
            .await();
    }
}

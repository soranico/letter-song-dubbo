/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.cluster.filter;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.UrlUtils;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.ProtocolServer;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.ClusterInvoker;

import java.util.List;

import static org.apache.dubbo.common.constants.CommonConstants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.SERVICE_FILTER_KEY;

/**
 * ListenerProtocol
 */
@Activate(order = 100)
public class ProtocolFilterWrapper implements Protocol {

    private final Protocol protocol;
    private static final FilterChainBuilder builder
            = ExtensionLoader.getExtensionLoader(FilterChainBuilder.class).getDefaultExtension();

    public ProtocolFilterWrapper(Protocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol == null");
        }
        this.protocol = protocol;
    }

    @Override
    public int getDefaultPort() {
        return protocol.getDefaultPort();
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        /** 
         * 注册的协议registry://
         * 服务发现协议 service-discovery-registry 第一次进来肯定是这两个之一
         *
         * 处理注册协议的
         * @see org.apache.dubbo.registry.integration.RegistryProtocol#export(Invoker)
         *
         */
        if (UrlUtils.isRegistry(invoker.getUrl())) {   
            return protocol.export(invoker);
        }
        /**
         * 这个时候注册的就是通信协议而非注册协议了,但他此时处在注册协议的执行过程中
         * @see org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper#export(Invoker)
         * 最终调用的是
         * @see org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol#export(Invoker)
         *
         */
        return protocol.export(builder.buildInvokerChain(invoker, SERVICE_FILTER_KEY, CommonConstants.PROVIDER));
    }

    @Override
    public <T> Invoker<T> refer(Class<T> type, URL url) throws RpcException {
        /**
         * 第一次进来是注册协议
         * @see org.apache.dubbo.rpc.protocol.ProtocolListenerWrapper#refer(Class, URL)
         */
        if (UrlUtils.isRegistry(url)) {
            return protocol.refer(type, url);
        }
        /**
         * 构建拦截器lian
         * @see DefaultFilterChainBuilder#buildClusterInvokerChain(ClusterInvoker, String, String)
         *
         * 返回的节点里面每个都包含一个真实调用的Invoker
         * @see FilterChainBuilder.ClusterFilterChainNode
         */
        return builder.buildInvokerChain(protocol.refer(type, url), REFERENCE_FILTER_KEY, CommonConstants.CONSUMER);
    }

    @Override
    public void destroy() {
        protocol.destroy();
    }

    @Override
    public List<ProtocolServer> getServers() {
        return protocol.getServers();
    }

}

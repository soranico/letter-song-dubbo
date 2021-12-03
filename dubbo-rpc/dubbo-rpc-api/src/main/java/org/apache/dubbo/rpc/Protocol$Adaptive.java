package org.apache.dubbo.rpc;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.protocol.AbstractInvoker;

public class Protocol$Adaptive implements Protocol {
    public Invoker refer(Class arg0, org.apache.dubbo.common.URL arg1) throws RpcException {
        if (arg1 == null) throw new IllegalArgumentException("url == null");
        org.apache.dubbo.common.URL url = arg1;
        String extName = ( url.getProtocol() == null ? "dubbo" : url.getProtocol() );/** 第一次进来是注册协议 */
        if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        Protocol extension = (Protocol)ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(extName);
        return extension.refer(arg0, arg1);/**@see org.apache.dubbo.rpc.protocol.ProtocolSerializationWrapper#refer(Class, URL)   */
    }
    public Exporter export(Invoker arg0) throws RpcException {
        if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        org.apache.dubbo.common.URL url = arg0.getUrl();/**@see AbstractInvoker#getUrl()  返回的就是原始的url */
        String extName = ( url.getProtocol() == null ? "dubbo" : url.getProtocol() );/** 第一次进来协议为 registry 本地的话为 injvm  */
        if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.Protocol) name from url (" + url.toString() + ") use keys([protocol])");
        Protocol extension = (Protocol)ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(extName);
        return extension.export(arg0);/** @see org.apache.dubbo.rpc.cluster.filter.ProtocolFilterWrapper#export(Invoker)  因为有Wrapper会先走Wrapper */
    }
    public java.util.List getServers()  {
        throw new UnsupportedOperationException("The method public default java.util.List org.apache.dubbo.rpc.Protocol.getServers() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
    }
    public void destroy()  {
        throw new UnsupportedOperationException("The method public abstract void org.apache.dubbo.rpc.Protocol.destroy() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
    }
    public int getDefaultPort()  {
        throw new UnsupportedOperationException("The method public abstract int org.apache.dubbo.rpc.Protocol.getDefaultPort() of interface org.apache.dubbo.rpc.Protocol is not adaptive method!");
    }
}

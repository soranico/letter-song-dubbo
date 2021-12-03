package org.apache.dubbo.rpc;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;

public class ProxyFactory$Adaptive implements ProxyFactory {
    public Invoker getInvoker(Object ref, Class interfaceClass, URL arg2) throws RpcException {
        if (arg2 == null) throw new IllegalArgumentException("url == null");
        URL url = arg2;/**@see org.apache.dubbo.rpc.proxy.javassist.JavassistProxyFactory#getInvoker(Object, Class, URL)  包装类最终调用 */
        String extName = url.getParameter("proxy", "javassist");/** 获取代理类型，没有指定使用javassist  */
        if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
        ProxyFactory extension = (ProxyFactory)ExtensionLoader.getExtensionLoader(ProxyFactory.class).getExtension(extName);/** 获取指定的代理工厂此时会被Warp包装 */
        return extension.getInvoker(ref, interfaceClass, arg2);/**@see org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper#getInvoker(Object, Class, URL)   此时是这个实现 */
    }
    public Object getProxy(Invoker arg0, boolean arg1) throws RpcException {
        if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        URL url = arg0.getUrl();
        String extName = url.getParameter("proxy", "javassist");
        if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
        ProxyFactory extension = (ProxyFactory)ExtensionLoader.getExtensionLoader(ProxyFactory.class).getExtension(extName);
        return extension.getProxy(arg0, arg1);/**@see org.apache.dubbo.rpc.proxy.wrapper.StubProxyFactoryWrapper#getProxy(Invoker, boolean)  */
    }
    public Object getProxy(Invoker arg0) throws RpcException {
        if (arg0 == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument == null");
        if (arg0.getUrl() == null) throw new IllegalArgumentException("org.apache.dubbo.rpc.Invoker argument getUrl() == null");
        URL url = arg0.getUrl();
        String extName = url.getParameter("proxy", "javassist");
        if(extName == null) throw new IllegalStateException("Failed to get extension (org.apache.dubbo.rpc.ProxyFactory) name from url (" + url.toString() + ") use keys([proxy])");
        ProxyFactory extension = (ProxyFactory)ExtensionLoader.getExtensionLoader(ProxyFactory.class).getExtension(extName);
        return extension.getProxy(arg0);
    }
}

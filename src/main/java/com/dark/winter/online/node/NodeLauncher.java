// Starts the standalone Java control-plane service with explicit network and TLS configuration.
package com.dark.winter.online.node;

import com.dark.winter.online.identity.ProfileFiles;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import javax.net.ssl.*;

/** Standalone Java 25 service entry point. Loopback is the default; external binds require explicit TLS configuration. */
public final class NodeLauncher {
    private NodeLauncher(){}
    public record Configuration(InetAddress bind,int port,Path data,boolean tls,Path keyStore,String passwordEnvironment,NodeIceConfiguration ice) {
        public Configuration(InetAddress bind,int port,Path data,boolean tls,Path keyStore,String passwordEnvironment){this(bind,port,data,tls,keyStore,passwordEnvironment,NodeIceConfiguration.empty());}
        @Override public String toString(){return "NodeConfiguration[port="+port+",tls="+tls+"]";}
    }
    public static JsonObject defaults() {
        JsonObject json=new JsonObject();json.addProperty("schema",1);json.addProperty("bind","127.0.0.1");json.addProperty("port",8787);json.addProperty("data","data");
        JsonObject tls=new JsonObject();tls.addProperty("enabled",false);tls.addProperty("keyStore","");tls.addProperty("passwordEnvironment","DARKWINTER_NODE_TLS_PASSWORD");json.add("tls",tls);json.add("ice",NodeIceConfiguration.defaults());return json;
    }
    public static Configuration configuration(Path config)throws IOException {
        try{return readConfiguration(config);}catch(RuntimeException invalid){throw new IOException("Invalid node configuration",invalid);}
    }
    private static Configuration readConfiguration(Path config)throws IOException {
        Path file=config.toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(file.getParent());
        JsonObject json=ProfileFiles.read(file,16384);ProfileFiles.fields(json,Set.of("schema","bind","port","data","tls"),Set.of("ice"));
        if(ProfileFiles.number(json,"schema")!=1)throw new IOException("Unsupported node configuration");
        InetAddress bind=literal(ProfileFiles.string(json,"bind",64));long port=ProfileFiles.number(json,"port");if(port<1||port>65535)throw new IOException("Invalid node port");
        String data=ProfileFiles.string(json,"data",2048);if(data.isBlank())throw new IOException("Missing node data directory");
        Path directory=file.getParent().resolve(data).toAbsolutePath().normalize();ProfileFiles.requireOutsideWorld(directory);
        JsonObject tls=json.getAsJsonObject("tls");ProfileFiles.fields(tls,Set.of("enabled","keyStore","passwordEnvironment"),Set.of());
        if(!tls.get("enabled").isJsonPrimitive()||!tls.get("enabled").getAsJsonPrimitive().isBoolean())throw new IOException("Invalid TLS setting");
        boolean enabled=tls.get("enabled").getAsBoolean();String keyStore=ProfileFiles.string(tls,"keyStore",2048),environment=ProfileFiles.string(tls,"passwordEnvironment",64);
        if(!environment.matches("[A-Z][A-Z0-9_]{0,63}"))throw new IOException("Invalid password environment name");
        if(!bind.isLoopbackAddress()&&!enabled)throw new IOException("Non-loopback binding requires explicit TLS");
        Path certificate=keyStore.isEmpty()?null:file.getParent().resolve(keyStore).toAbsolutePath().normalize();
        if(enabled&&(certificate==null||Files.isSymbolicLink(certificate)||!Files.isRegularFile(certificate,LinkOption.NOFOLLOW_LINKS)||Files.size(certificate)>4*1024*1024))
            throw new IOException("TLS requires an existing bounded PKCS12 key store");
        NodeIceConfiguration ice=json.has("ice")?NodeIceConfiguration.parse(json.getAsJsonObject("ice")):NodeIceConfiguration.empty();
        return new Configuration(bind,(int)port,directory,enabled,certificate,environment,ice);
    }
    public static void initialize(Path file)throws IOException {
        Path target=file.toAbsolutePath().normalize();ProfileFiles.directory(target.getParent());ProfileFiles.atomic(target,defaults(),false);
    }
    private static InetAddress literal(String value)throws IOException {
        if(value.contains(":")){
            if(!value.matches("[0-9a-fA-F:]{2,45}"))throw new IOException("Use a literal IPv6 bind address");
            InetAddress address=InetAddress.getByName(value);if(!(address instanceof Inet6Address))throw new IOException("Invalid IPv6 bind address");return address;
        }
        String[] parts=value.split("\\.",-1);if(parts.length!=4)throw new IOException("Use a literal IPv4 bind address");
        byte[] bytes=new byte[4];for(int i=0;i<4;i++){
            if(!parts[i].matches("0|[1-9][0-9]{0,2}"))throw new IOException("Invalid IPv4 bind address");
            int part=Integer.parseInt(parts[i]);if(part>255)throw new IOException("Invalid IPv4 bind address");bytes[i]=(byte)part;
        }return InetAddress.getByAddress(bytes);
    }
    private static SSLContext tls(Configuration config)throws IOException {
        if(!config.tls())return null;String supplied=System.getenv(config.passwordEnvironment());char[] password=supplied==null?null:supplied.toCharArray();
        try{
            if(password==null||password.length<1||password.length>1024)throw new IOException("TLS password environment is missing");
            KeyStore keys=KeyStore.getInstance("PKCS12");try(InputStream input=Files.newInputStream(config.keyStore())){keys.load(input,password);}
            KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(keys,password);
            SSLContext context=SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,new SecureRandom());return context;
        }catch(GeneralSecurityException failure){throw new IOException("TLS configuration could not be loaded",failure);}
        finally{if(password!=null)Arrays.fill(password,'\0');}
    }
    public static void main(String[] args) {
        try{
            String operation="run";Path file=Path.of("node-config.json");
            if(args.length!=0){
                if(args.length!=2||!Set.of("--config","--init-config","--check-config").contains(args[0]))throw new IOException("Use --config, --init-config or --check-config with one file");
                operation=args[0].equals("--init-config")?"init":args[0].equals("--check-config")?"check":"run";file=Path.of(args[1]);
            }
            if(operation.equals("init")){initialize(file);System.out.println("Создана локальная конфигурация. Публичное прослушивание выключено.");return;}
            if(operation.equals("run")&&!Files.exists(file,LinkOption.NOFOLLOW_LINKS))initialize(file);
            Configuration config=configuration(file);
            if(operation.equals("check")){System.out.println("Конфигурация корректна; служба не запускалась.");return;}
            SSLContext secure=tls(config);
            try(DirectoryNode node=new DirectoryNode(config.data(),new InetSocketAddress(config.bind(),config.port()),secure,Clock.systemUTC(),config.ice())){
                CountDownLatch stopping=new CountDownLatch(1);Thread hook=new Thread(()->{try{node.close();}catch(IOException ignored){}stopping.countDown();},"DarkWinter-node-stop");
                Runtime.getRuntime().addShutdownHook(hook);
                System.out.println("Служба друзей DarkWinter запущена: "+(secure==null?"http":"https")+"://"+(config.bind() instanceof Inet6Address?"["+config.bind().getHostAddress()+"]":config.bind().getHostAddress())+":"+node.address().getPort());
                System.out.println("Публичный ID узла: "+node.profile().id());
                System.out.println("Игровой мир не запущен. Для остановки: Ctrl+C.");
                try{stopping.await();}finally{try{Runtime.getRuntime().removeShutdownHook(hook);}catch(IllegalStateException shutdown){}}
            }
        }catch(Exception failure){
            // Never print configuration values, passwords, certificates, envelopes, peer keys or exception payloads.
            System.err.println("Служба не запущена. Проверьте конфигурацию, занятость порта и права каталога. Код: "+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}

package MultiplyDowloader;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static MultiplyDowloader.Main.println;

/**
 * 返回值是文件类型
 */
public class Check implements Callable<Map<String, String>> {

    /**
     * 作为线程执行体的返回值，包含Content-Length和Content-Type
     */
    private final Map<String, String> map;
    private final String checkPath;

    public Check(String checkPath) {
        this.checkPath = checkPath;
        this.map = new HashMap<>();
    }

    @Override
    public Map<String, String> call() {
        try (var client = HttpClients.createDefault()) {
            var get  = new HttpGet(checkPath);
            try (var response = client.execute(get)) {
                println(response.getStatusLine());
                if (response.getStatusLine().getStatusCode() == 200) {
                    map.put("Content-Type", response.getEntity().getContentType().toString());
                    map.put("Content-Length", response.getEntity().getContentLength()+"");
                    Arrays.stream(response.getAllHeaders()).forEach(Main::println);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return map;
    }

    public Map<String, String> getMap() {
        return Map.copyOf(map);
    }

    /**
    public static void main(String[] args) throws Exception{
        //FutureTask<Map<String, String>> fu = new FutureTask<>(new Check("http://dl.mqego.com/soft1/navicatmysqlfront.zip"));
        //Thread.ofVirtual().start(fu);
        //println(fu.get());
    }
     */
}

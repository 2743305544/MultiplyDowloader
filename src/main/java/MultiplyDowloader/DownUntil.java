package MultiplyDowloader;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class DownUntil {
    /**
     * 下载链接
     */
    private final String urlPath;
    /**
     * 文件保存路径
     */
    private String fileToSave;
    /**
     * 文件大小
     */
    private long fileSize;
    /**
     * 下载使用的线程数量，根据文件大小动态调整
     */
    private int threadNum;
    /**
     * 文件类型
     */
    private String contentType;
    /**
     * 线程组，用于下载
     */
    private DowloadThread[] threads;
    /**
     * 正在运行的下载线程个数
     */
    private final AtomicInteger runningThread = new AtomicInteger(0);
    /**
     * 上次计算的下载速度，用于动态调整缓冲区大小
     */
    private double lastSpeed = 0;
    /**
     * 上次计算速度的时间
     */
    private long lastSpeedTime = 0;
    /**
     * 上次计算的总下载大小
     */
    private long lastTotalSize = 0;
    /**
     * 记录每个线程的下载速度
     */
    private final ConcurrentHashMap<Integer, Double> threadSpeeds = new ConcurrentHashMap<>();
    /**
     * 记录开始下载的时间
     */
    private long startTime;
    /**
     * 当前的下载速度 (bytes/s)
     */
    private final AtomicLong currentSpeed = new AtomicLong(0);
    /**
     * 预计剩余时间 (秒)
     */
    private final AtomicLong estimatedTimeRemaining = new AtomicLong(0);
    /**
     * 记录位置的频率 (字节)
     */
    private static final long POSITION_RECORD_FREQUENCY = 10 * 1024 * 1024; // 10MB

    /**
     * @param urlPath     下载链接
     * @param fileToSave  文件保存路径
     * @param fileSize    文件大小
     * @param contentType 文件类型
     */
    public DownUntil(String urlPath, String fileToSave, long fileSize, String contentType) {
        this.urlPath = urlPath;

        String tempFilePath;
        /**
         * 如果传入的路径是个文件夹，则自己给它命名
         */
        if (Files.isDirectory(Paths.get(fileToSave))) {
            String name = getName();
            tempFilePath = Paths.get(fileToSave, Objects.requireNonNullElse(name, "dowload.dowload")).toString();
        } else {
            tempFilePath = fileToSave;
        }

        // 检查文件是否已存在，如果存在则生成随机文件名
        this.fileToSave = ensureUniqueFileName(tempFilePath);

        /**
         * 如果传入的文件大小或者文件类型是空的话
         * 再进行一次验证
         */
        if (fileSize == 0 || contentType == null) {
            try {
                var check = new Check(urlPath);
                var future = new FutureTask<>(check);
                Thread.startVirtualThread(future);
                var map = future.get();
                this.fileSize = Long.parseLong(map.getOrDefault("Content-Length", "-1"));
                this.contentType = map.getOrDefault("Content-Type", "type/dowload").split("\\:\\s?")[1];
            } catch (Exception e) {
                e.printStackTrace();
                this.fileSize = fileSize;
                this.contentType = contentType;
            }
        } else {
            this.fileSize = fileSize;
            this.contentType = contentType;
        }

        // 根据文件大小动态调整线程数
        this.threadNum = calculateOptimalThreadCount(this.fileSize);
        this.threads = new DowloadThread[threadNum];
    }

    /**
     * 根据文件大小计算最优线程数
     * @param fileSize 文件大小
     * @return 最优线程数
     */
    private int calculateOptimalThreadCount(long fileSize) {
        int processorCount = Runtime.getRuntime().availableProcessors();

        // 对于小文件，使用较少的线程
        if (fileSize < 1024 * 1024) { // 小于1MB
            return Math.max(1, processorCount / 4);
        }
        // 对于中等大小的文件
        else if (fileSize < 100 * 1024 * 1024) { // 小于100MB
            return processorCount;
        }
        // 对于大文件，使用更多的线程
        else {
            return Math.min(32, processorCount * 2); // 最多32个线程
        }
    }

    /**
     * 确保文件名唯一，如果文件已存在则添加随机后缀
     * @param filePath 原始文件路径
     * @return 唯一的文件路径
     */
    private String ensureUniqueFileName(String filePath) {
        var file = new File(filePath);
        if (!file.exists()) {
            return filePath;
        }

        // 文件已存在，生成带随机数的文件名
        var path = Paths.get(filePath);
        var parent = path.getParent();
        var fileName = path.getFileName().toString();

        // 分离文件名和扩展名
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;
        String extension = (dotIndex > 0) ? fileName.substring(dotIndex) : "";

        // 生成随机数并创建新文件名
        var random = new java.util.Random();
        String newFileName;
        File newFile;

        do {
            // 生成6位随机数
            int randomNum = random.nextInt(900000) + 100000;
            newFileName = baseName + "_" + randomNum + extension;
            newFile = parent != null ? new File(parent.toString(), newFileName) : new File(newFileName);
        } while (newFile.exists());

        return newFile.getAbsolutePath();
    }

    /**
     * 下载方法
     * @return 0表示成功启动下载，-1表示失败
     */
    public long dowload() {
        try {
            startTime = System.currentTimeMillis();
            /**
             * 计算每个线程需要下载的块大小
             */
            var currentPartSize = fileSize / threadNum + 1;
            /**
             * 预先划分一个所需文件大小的空间
             */
            try (var raf = new RandomAccessFile(fileToSave, "rw")) {
                raf.setLength(fileSize);
            }
            /**
             * 开启线程
             */
            for (int i = 0; i < threadNum; i++) {
                var startPos = i * currentPartSize;
                var currentPart = new RandomAccessFile(fileToSave, "rw");
                currentPart.seek(startPos);
                threads[i] = new DowloadThread(startPos, currentPartSize, currentPart, i);
                Thread.startVirtualThread(threads[i]);
                runningThread.incrementAndGet();
            }

            // 启动速度监控线程
            Thread.startVirtualThread(this::monitorDownloadSpeed);

        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
        return 0;
    }

    /**
     * 监控下载速度并更新相关指标
     */
    private void monitorDownloadSpeed() {
        try {
            while (getSchedule() < 1.0) {
                long currentTime = System.currentTimeMillis();
                long currentTotalSize = getTotalDownloadedSize();

                // 计算时间差和大小差
                long timeDiff = currentTime - lastSpeedTime;
                long sizeDiff = currentTotalSize - lastTotalSize;

                if (timeDiff > 0) {
                    // 计算当前速度 (bytes/s)
                    double speed = (sizeDiff * 1000.0) / timeDiff;
                    currentSpeed.set((long) speed);

                    // 计算剩余时间
                    long remainingBytes = fileSize - currentTotalSize;
                    if (speed > 0) {
                        long remainingTimeSeconds = (long) (remainingBytes / speed);
                        estimatedTimeRemaining.set(remainingTimeSeconds);
                    }

                    // 更新上次测量的值
                    lastSpeed = speed;
                    lastSpeedTime = currentTime;
                    lastTotalSize = currentTotalSize;
                }

                Thread.sleep(1000); // 每秒更新一次
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取当前下载速度
     * @return 下载速度 (bytes/s)
     */
    public long getCurrentSpeed() {
        return currentSpeed.get();
    }

    /**
     * 获取预计剩余时间
     * @return 剩余时间 (秒)
     */
    public long getEstimatedTimeRemaining() {
        return estimatedTimeRemaining.get();
    }

    /**
     * 获取已下载的总字节数
     * @return 已下载的字节数
     */
    private long getTotalDownloadedSize() {
        long sumSize = 0;
        for (int i = 0; i < threadNum; i++) {
            if (threads[i] != null) {
                sumSize += threads[i].length;
            }
        }
        return sumSize;
    }

    /**
     * 由外部调用来获取下载进度
     * @return 下载进度，0.0-1.0之间的小数
     */
    public double getSchedule() {
        return getTotalDownloadedSize() * 1.0 / fileSize;
    }

    /**
     * 当输入的fileToSave不是文件的话，下列方法可以推断出一个文件名
     * @return 推断的文件名
     */
    private String getName() {
        int pos = urlPath.lastIndexOf("/") + 1;
        String name = urlPath.substring(pos);
        if (Pattern.matches("^\\S{3,20}\\.\\S{3,10}", name)) {
            return name;
        } else if (MimeUtils.hasMimeType(contentType)) {
            var dateformat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            return "Dowload" + dateformat.format(new Date()) + "." + MimeUtils.guessExtensionFromMimeType(contentType);
        }
        return null;
    }

    /**
     * 线程子类进行分片下载
     */
    private class DowloadThread extends Thread {
        private final int threadId;
        public long length = 0;
        private long startPos;
        private final long currentPartSize;
        private final RandomAccessFile currentPart;
        private long lastRecordPosition = 0;

        public DowloadThread(long start, long dowloadPartSize, RandomAccessFile currentPart, int threadId) {
            this.startPos = start;
            this.currentPartSize = dowloadPartSize;
            this.currentPart = currentPart;
            this.threadId = threadId;
        }

        @Override
        public void run() {
            try (var client = HttpClients.createDefault()) {
                var get = new HttpGet(urlPath);
                get.setHeader("Accept", "*/*");
                get.addHeader("Accept-Language", "zh=CN");
                get.addHeader("Charset", "UTF-8");

                try (var response = client.execute(get)) {
                    if (response.getStatusLine().getStatusCode() == 200) {
                        var file = new File(fileToSave + threadId + ".tmp");
                        if (file.exists() && file.length() > 0) {
                            /**
                             * 如果之前的缓存文件存在的话，读取之前缓存文件的写入位置
                             */
                            try (var fis = new FileInputStream(file);
                                 var br = new BufferedReader(new InputStreamReader(fis))) {
                                var lastPosition = br.readLine();
                                var last = Long.parseLong(lastPosition);
                                length = last - startPos;
                                startPos = last;
                                currentPart.seek(startPos);
                                lastRecordPosition = startPos;
                            }
                        }

                        try (var in = response.getEntity().getContent()) {
                            in.skip(startPos);

                            // 动态调整缓冲区大小
                            int bufferSize = calculateOptimalBufferSize(fileSize, threadId);
                            var buffer = new byte[bufferSize];

                            int hasRead;
                            long total = 0;
                            long lastSpeedCalcTime = System.currentTimeMillis();
                            long lastSpeedCalcSize = 0;

                            while (length < currentPartSize && (hasRead = in.read(buffer)) != -1) {
                                currentPart.write(buffer, 0, hasRead);
                                length += hasRead;
                                total += hasRead;

                                // 计算此线程的下载速度
                                long now = System.currentTimeMillis();
                                long timeDiff = now - lastSpeedCalcTime;
                                if (timeDiff > 2000) { // 每2秒计算一次速度
                                    double speed = ((total - lastSpeedCalcSize) * 1000.0) / timeDiff;
                                    threadSpeeds.put(threadId, speed);

                                    // 动态调整缓冲区大小
                                    bufferSize = calculateOptimalBufferSize(fileSize, threadId);
                                    if (bufferSize != buffer.length) {
                                        buffer = new byte[bufferSize];
                                    }

                                    lastSpeedCalcTime = now;
                                    lastSpeedCalcSize = total;
                                }

                                /**
                                 * 减少临时文件I/O，每10MB记录一次位置
                                 */
                                long currentThreadPos = startPos + total;
                                if (currentThreadPos - lastRecordPosition >= POSITION_RECORD_FREQUENCY) {
                                    try (var raff = new RandomAccessFile(fileToSave + threadId + ".tmp", "rwd")) {
                                        raff.write(String.valueOf(currentThreadPos).getBytes());
                                    }
                                    lastRecordPosition = currentThreadPos;
                                }
                            }

                            // 确保最后的位置被记录
                            long finalPos = startPos + total;
                            if (finalPos > lastRecordPosition) {
                                try (var raff = new RandomAccessFile(fileToSave + threadId + ".tmp", "rwd")) {
                                    raff.write(String.valueOf(finalPos).getBytes());
                                }
                            }
                        }

                        currentPart.close();

                        /**
                         * 如果runningThread为0的话，删除所有缓存文件
                         */
                        if (runningThread.decrementAndGet() == 0) {
                            for (int i = 0; i < threadNum; i++) {
                                try {
                                    Files.delete(Paths.get(fileToSave + i + ".tmp"));
                                } catch (IOException e) {
                                    // 忽略删除失败的异常
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 计算最优缓冲区大小
     * @param fileSize 文件大小
     * @param threadId 线程ID
     * @return 最优缓冲区大小
     */
    private int calculateOptimalBufferSize(long fileSize, int threadId) {
        // 获取此线程的下载速度
        double speed = threadSpeeds.getOrDefault(threadId, 0.0);

        // 基于文件大小的基础缓冲区大小
        int baseSize;
        if (fileSize < 1024 * 1024) { // < 1MB
            baseSize = 16 * 1024; // 16KB
        } else if (fileSize < 10 * 1024 * 1024) { // < 10MB
            baseSize = 64 * 1024; // 64KB
        } else if (fileSize < 100 * 1024 * 1024) { // < 100MB
            baseSize = 256 * 1024; // 256KB
        } else if (fileSize < 1024 * 1024 * 1024) { // < 1GB
            baseSize = 1024 * 1024; // 1MB
        } else {
            baseSize = 4 * 1024 * 1024; // 4MB
        }

        // 根据下载速度调整缓冲区大小
        if (speed > 10 * 1024 * 1024) { // > 10MB/s
            return Math.min(8 * 1024 * 1024, baseSize * 2); // 最大8MB
        } else if (speed > 5 * 1024 * 1024) { // > 5MB/s
            return Math.min(4 * 1024 * 1024, baseSize * 2); // 最大4MB
        } else if (speed > 1024 * 1024) { // > 1MB/s
            return baseSize;
        } else if (speed > 500 * 1024) { // > 500KB/s
            return Math.max(64 * 1024, baseSize / 2); // 最小64KB
        } else {
            return Math.max(32 * 1024, baseSize / 4); // 最小32KB
        }
    }
}

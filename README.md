# 多线程下载器

## 修改
***
对老项目无法启动进行修复
并对老项目进行Jdk的升级
并且进行了一些优化

## 功能：
***

* 断点续传
* 启动时自动检查剪切板
* 用户界面友好
* 基于JDK 21的虚拟线程实现高效并发下载

## 技术特性
***

* 使用JDK 21的虚拟线程（Virtual Threads）进行高效并发操作
* 利用现代Java特性如var类型推断、增强的switch表达式
* 使用try-with-resources自动资源管理
* 采用AtomicInteger进行线程安全计数
* 使用Lambda表达式和函数式编程
* 根据文件大小自动选择缓冲区大小和线程数量

## 实现
***

首先上成果图,如下

![UI界面](https://raw.githubusercontent.com/crazyStrome/myPhote/master/UI.PNG)

打开该软件，会检查剪贴板里面是否保存着链接，如果链接的格式匹配成功的话，直接显示出来。

![检查链接](https://cdn-std.dprcdn.net/files/acc_615569/7fIc0?download)

单击**验证**按钮，会进行验证，以下是验证成功的界面，显示了文件的大小和类型。

![连接验证成功](https://cdn-std.dprcdn.net/files/acc_615569/e7vmwB?download)

单击**路径**按钮，会弹出如下界面进行存放路径选择。

![路径选择](https://cdn-std.dprcdn.net/files/acc_615569/2Uy02?download)

选择路径之后，软件会根据下载链接和之前验证的文件类型来推断出来文件名字以及后缀并添加到文本框中去。

![自动完成文本](https://cdn-std.dprcdn.net/files/acc_615569/GHnmPM?download)

单击**下载**按钮，会进行下载，如果之前被中断过的话，可以断点续传。

![下载1](https://cdn-std.dprcdn.net/files/acc_615569/BUgf3z?download)

![下载1](https://cdn-std.dprcdn.net/files/acc_615569/VwWD3y?download)

会在文件保存路径生成缓存文件，下载完成后自动删除。

![](https://cdn-std.dprcdn.net/files/acc_615569/9PhTcq?download)

## 代码
***

[Github](https://github.com/crazyStrome/MultiplyDowloader)

主要有四个文件：

<span id="Main">**Main.class**</span>

<span id="Check">**Check.class**</span>

<span id="MimeUtils">**MimeUtils.class**</span>

<span id="DownUtil">**DownUtil**</span>
***

### [Main](https://github.com/crazyStrome/MultiplyDowloader/blob/master/src/main/java/MultiplyDowloader/Main.java)

Main类负责创建和管理用户界面，处理用户交互。在JDK 21版本中，我们使用了以下现代Java特性：

- 使用`var`关键字进行局部变量类型推断
- 使用Lambda表达式替代匿名内部类
- 使用`Thread.startVirtualThread()`创建轻量级虚拟线程
- 使用增强的switch表达式处理文件大小格式化
- 使用`Duration` API处理时间格式化

例如，使用虚拟线程进行下载监控：

```java
Thread.startVirtualThread(() -> {
    double schedule = 0;
    long start = System.currentTimeMillis();
    statusField.setText(TAG + "正在下载");
    while ((schedule = downUntil.getSchedule()) < 1d) {
        dowloadProgress.setString(String.format("%.2f", schedule*100) + "%");
        dowloadProgress.setValue((int) (schedule * 100));
        dowloadProgress.validate();
        try {
            Thread.sleep(400);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }
    long end = System.currentTimeMillis();
    dowloadProgress.setValue(100);
    dowloadProgress.setString("下载完成");
    statusField.setText(TAG + "下载用时: " + excuteTime(end-start));
});
```

由于swing的FileChooser界面太丑了，所以我在初始化开头放了一段代码，使整体的代码具有Windows的风格。

```java
/**
 * 使整个UI和平台相关，可以美化Windows环境下的JFileChooser
 */
if (UIManager.getLookAndFeel().isSupportedLookAndFeel()) {
    final String platform = UIManager.getSystemLookAndFeelClassName();
    if (!UIManager.getLookAndFeel().getName().equals(platform)) {
        try {
            UIManager.setLookAndFeel(platform);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

还有一个是在初始化时对剪贴板进行检测，如果剪贴板中的内容是链接的话，直接放到URL的文本框中去。

```java
/**
 * 在启动时查看系统剪贴板中的内容
 * 如果有链接，就直接复制到urlField上去
 */
var sysClip = Toolkit.getDefaultToolkit().getSystemClipboard();
var clipTf = sysClip.getContents(null);
if (clipTf != null) {
    if (clipTf.isDataFlavorSupported(DataFlavor.stringFlavor)) {
        try {
            var ret = (String) clipTf.getTransferData(DataFlavor.stringFlavor);
            var check = "((http|ftp|https)://)(([a-zA-Z0-9\\._-]+\\.[a-zA-Z]{2,6})|([0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}))(:[0-9]{1,4})*(/[a-zA-Z0-9\\&%_\\./-~-]*)?";
            if (Pattern.matches(check, ret)) {
                statusField.setText(TAG + "检测到剪贴板的链接");
                urlField.setText(ret);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### [Check](https://github.com/crazyStrome/MultiplyDowloader/blob/master/src/main/java/MultiplyDowloader/Check.java)

该类主要是继承了Callable接口，是多线程编程的另一种实现方法。
多线程编程主要三个方式：

* 继承自Thread类并重写run方法
* 实现Runnable接口的run方法，并传入Thread的构造方法
* 继承自Callable接口，并用FutureTask实现

在JDK 21版本中，我们使用了虚拟线程和try-with-resources来优化资源管理：

```java
@Override
public Map<String, String> call() {
    try (var client = HttpClients.createDefault()) {
        var get = new HttpGet(checkPath);
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
```

### [MimeUtils](https://github.com/crazyStrome/MultiplyDowloader/blob/master/src/main/java/MultiplyDowloader/MimeUtils.java)

为了推断文件的后缀名，我使用获取的Mime类型进行查找，该类是小米公司开源的内容。

### [DownUtil](https://github.com/crazyStrome/MultiplyDowloader/blob/master/src/main/java/MultiplyDowloader/DownUntil.java)

DownUntil类是下载功能的核心实现，在JDK 21版本中，我们使用了以下现代Java特性：

- 使用`var`关键字进行局部变量类型推断
- 使用`Thread.startVirtualThread()`创建轻量级虚拟线程
- 使用try-with-resources自动关闭资源
- 使用`AtomicInteger`进行线程安全计数
- 使用`Objects.requireNonNullElse()`简化空值处理

例如，使用虚拟线程进行分片下载：

```java
for (int i = 0; i < threadNum; i++) {
    var startPos = i * currentPartSize;
    var currentPart = new RandomAccessFile(fileToSave, "rw");
    currentPart.seek(startPos);
    threads[i] = new DowloadThread(startPos, currentPartSize, currentPart, i);
    Thread.startVirtualThread(threads[i]);
    runningThread.incrementAndGet();
}
```

## 系统要求
***

* JDK 21或更高版本
* 支持虚拟线程的Java运行时
* 足够的网络带宽进行多线程下载

## 构建与运行
***

使用Gradle构建项目：

```bash
gradle build
```

运行项目：

```bash
gradle run
```

或者直接运行生成的JAR文件：

```bash
java -jar build/libs/MultiplyDowloader-1.0.jar

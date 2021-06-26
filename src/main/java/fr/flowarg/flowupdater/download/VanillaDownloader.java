package fr.flowarg.flowupdater.download;

import fr.flowarg.flowio.FileUtils;
import fr.flowarg.flowlogger.ILogger;
import fr.flowarg.flowupdater.FlowUpdater;
import fr.flowarg.flowupdater.download.json.AssetDownloadable;
import fr.flowarg.flowupdater.download.json.Downloadable;
import fr.flowarg.flowupdater.utils.IOUtils;
import fr.flowarg.flowzipper.ZipUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class VanillaDownloader
{
    private final Path dir;
    private final ILogger logger;
    private final IProgressCallback callback;
    private final DownloadInfos downloadInfos;
    private final boolean reExtractNatives;
    private final int threadsForAssets;

    private final Path natives;
    private final Path assets;

    public VanillaDownloader(Path dir, FlowUpdater flowUpdater) throws IOException
    {
        this.dir = dir;
        this.logger = flowUpdater.getLogger();
        this.callback = flowUpdater.getCallback();
        this.downloadInfos = flowUpdater.getDownloadInfos();
        this.reExtractNatives = flowUpdater.getUpdaterOptions().isReExtractNatives();
        this.threadsForAssets = flowUpdater.getUpdaterOptions().getNmbrThreadsForAssets();

        this.natives = Paths.get(this.dir.toString(), "natives");
        this.assets = Paths.get(this.dir.toString(), "assets");

        Files.createDirectories(Paths.get(this.dir.toString(), "libraries"));
        Files.createDirectories(this.assets);
        Files.createDirectories(this.natives);

        this.downloadInfos.init();
    }

    public void download() throws Exception
    {
        this.checkAllLibraries();
        this.downloadAssets();
        this.extractNatives();

        this.logger.info("All vanilla files are successfully downloaded !");
    }

    private void checkAllLibraries() throws Exception
    {
        this.logger.info("Checking library files...");
        this.callback.step(Step.DL_LIBS);

        Files.list(this.natives).filter(Files::isDirectory).forEach(path -> {
            try
            {
                FileUtils.deleteDirectory(path);
            } catch (IOException e)
            {
                this.logger.printStackTrace(e);
            }
        });

        for (Downloadable downloadable : this.downloadInfos.getLibraryDownloadables())
        {
            final Path filePath = Paths.get(this.dir.toString(), downloadable.getName());

            if(Files.notExists(filePath) || !FileUtils.getSHA1(filePath).equalsIgnoreCase(downloadable.getSha1()) || FileUtils.getFileSizeBytes(filePath) != downloadable.getSize())
            {
                IOUtils.download(this.logger, new URL(downloadable.getUrl()), filePath);
                this.callback.onFileDownloaded(filePath);
            }

            this.downloadInfos.incrementDownloaded(downloadable.getSize());
            this.callback.update(this.downloadInfos.getDownloadedBytes(), this.downloadInfos.getTotalToDownloadBytes());
        }
    }

    private void extractNatives() throws IOException
    {
        boolean flag = false;
        final List<Path> existingNatives = Files.list(this.natives).collect(Collectors.toList());
        if(!existingNatives.isEmpty())
        {
            for (Path minecraftNative : Files.list(this.natives).filter(path -> path.getFileName().toString().endsWith(".jar")).collect(Collectors.toList()))
            {
                final JarFile jarFile = new JarFile(minecraftNative.toFile());
                final Enumeration<? extends ZipEntry> entries = jarFile.entries();
                while (entries.hasMoreElements())
                {
                    final ZipEntry entry = entries.nextElement();
                    if(!entry.isDirectory() && !(entry.getName().endsWith(".git") || entry.getName().endsWith(".sha1") || entry.getName().contains("META-INF")))
                    {
                        final Path flPath = Paths.get(this.natives.toString(), entry.getName());
                        System.out.println(flPath);
                        if (!Files.exists(flPath) || entry.getCrc() != FileUtils.getCRC32(flPath))
                            flag = true;
                    }
                }
                jarFile.close();
            }
        }

        if (this.reExtractNatives || flag)
        {
            this.logger.info("Extracting natives...");
            this.callback.step(Step.EXTRACT_NATIVES);

            Files.list(this.natives)
                    .filter(file -> !Files.isDirectory(file) && file.getFileName().toString().endsWith(".jar"))
                    .forEach(file -> {
                        try
                        {
                            ZipUtils.unzipJar(this.natives.toString(), file.toString(), "ignoreMetaInf");
                        } catch (IOException e)
                        {
                            this.logger.printStackTrace(e);
                        }
                    });
        }

        Files.list(this.natives)
                .filter(file -> file.getFileName().toString().endsWith(".git") || file.getFileName().toString().endsWith(".sha1"))
                .forEach(path -> {
                    try
                    {
                        Files.delete(path);
                    } catch (IOException e)
                    {
                        this.logger.printStackTrace(e);
                    }
                });
    }

    private void downloadAssets()
    {
        this.logger.info("Checking assets...");
        this.callback.step(Step.DL_ASSETS);
        final ThreadPoolExecutor threadPool = (ThreadPoolExecutor)Executors.newFixedThreadPool(this.threadsForAssets);
        for (int i = 0; i < threadPool.getMaximumPoolSize(); i++)
        {
            threadPool.submit(() -> {
                try {
                    AssetDownloadable assetDownloadable;
                    while ((assetDownloadable = this.downloadInfos.getAssetDownloadables().poll()) != null)
                    {
                        final Path downloadPath = Paths.get(this.assets.toString(), assetDownloadable.getFile());

                        if (Files.notExists(downloadPath) || FileUtils.getFileSizeBytes(downloadPath) != assetDownloadable.getSize())
                        {
                            final Path localAssetPath = Paths.get(IOUtils.getMinecraftFolder().toString(), "assets", assetDownloadable.getFile());
                            if(Files.exists(localAssetPath) && FileUtils.getFileSizeBytes(localAssetPath) == assetDownloadable.getSize()) IOUtils.copy(this.logger, localAssetPath, downloadPath);
                            else
                            {
                                IOUtils.download(this.logger, assetDownloadable.getUrl(), downloadPath);
                                this.callback.onFileDownloaded(downloadPath);
                            }
                        }

                        this.downloadInfos.incrementDownloaded(assetDownloadable.getSize());
                        this.callback.update(this.downloadInfos.getDownloadedBytes(), this.downloadInfos.getTotalToDownloadBytes());
                    }
                } catch (Exception e)
                {
                    this.logger.printStackTrace(e);
                }
            });
        }
        try
        {
            threadPool.shutdown();
            threadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e)
        {
            this.logger.printStackTrace(e);
        }
    }
}

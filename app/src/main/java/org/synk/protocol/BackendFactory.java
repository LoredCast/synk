package org.synk.protocol;

import org.synk.core.SyncModels.AccountConfig;

public final class BackendFactory {
    private BackendFactory() {}

    public static RemoteBackend create(AccountConfig cfg) {
        if (cfg == null) throw new IllegalArgumentException("AccountConfig is null");
        switch (cfg.protocol) {
            case FTP:
            case FTPS:
                return new FtpBackend(cfg);
            case S3:
                return new S3Backend(cfg);
            case LOCAL:
                return new LocalBackend(cfg);
            case WEBDAV:
            default:
                return new WebDavBackend(cfg);
        }
    }
}

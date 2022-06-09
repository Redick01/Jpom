/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Code Technology Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.jpom;

import cn.hutool.core.date.BetweenFormatter;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.SystemClock;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.jiangzeyin.common.EnableCommonBoot;
import cn.jiangzeyin.common.spring.SpringUtil;
import cn.jiangzeyin.common.spring.event.ApplicationEventLoad;
import io.jpom.common.Type;
import io.jpom.common.interceptor.IpInterceptor;
import io.jpom.common.interceptor.LoginInterceptor;
import io.jpom.common.interceptor.OpenApiInterceptor;
import io.jpom.common.interceptor.PermissionInterceptor;
import io.jpom.model.data.BackupInfoModel;
import io.jpom.model.data.SystemIpConfigModel;
import io.jpom.service.dblog.BackupInfoService;
import io.jpom.service.system.SystemParametersServer;
import io.jpom.service.user.UserService;
import io.jpom.system.db.DbConfig;
import io.jpom.system.init.InitDb;
import io.jpom.util.StringUtil;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;

import java.io.File;
import java.util.concurrent.Future;

/**
 * jpom 启动类
 *
 * @author jiangzeyin
 * @since 2017/9/14
 */
@SpringBootApplication
@ServletComponentScan
@EnableCommonBoot
public class JpomServerApplication implements ApplicationEventLoad {

    /**
     * 参数
     */
    private static String[] ARGS;

    /**
     * 启动执行
     * --rest:ip_config 重置 IP 白名单配置
     * --rest:load_init_db 重新加载数据库初始化操作
     * --rest:super_user_pwd 重置超级管理员密码
     * --recover:h2db 当 h2 数据出现奔溃无法启动需要执行恢复逻辑
     * --close:super_user_mfa 重置超级管理员 mfa
     * --backup-h2 备份数据库
     * --import-h2-sql=/xxxx.sql 导入指定文件 sql
     *
     * @param args 参数
     * @throws Exception 异常
     */
    public static void main(String[] args) throws Exception {
        long time = SystemClock.now();
        ARGS = args;
        //
        JpomApplication jpomApplication = new JpomApplication(Type.Server, JpomServerApplication.class, args);
        jpomApplication
            // 拦截器
            .addInterceptor(IpInterceptor.class)
            .addInterceptor(LoginInterceptor.class)
            .addInterceptor(OpenApiInterceptor.class)
            .addInterceptor(PermissionInterceptor.class)
            .run(args);
        // 重置 ip 白名单配置
        if (ArrayUtil.containsIgnoreCase(args, "--rest:ip_config")) {
            SystemParametersServer parametersServer = SpringUtil.getBean(SystemParametersServer.class);
            parametersServer.delByKey(SystemIpConfigModel.ID);
            Console.log("Clear IP whitelist configuration successfully");
        }
        //  重置超级管理员密码
        if (ArrayUtil.containsIgnoreCase(args, "--rest:super_user_pwd")) {
            UserService userService = SpringUtil.getBean(UserService.class);
            String restResult = userService.restSuperUserPwd();
            if (restResult != null) {
                Console.log(restResult);
            } else {
                Console.log("There is no super administrator account in the system");
            }
        }
        // 关闭超级管理员 mfa
        if (ArrayUtil.containsIgnoreCase(args, "--close:super_user_mfa")) {
            UserService userService = SpringUtil.getBean(UserService.class);
            String restResult = userService.closeSuperUserMfa();
            if (restResult != null) {
                Console.log(restResult);
            } else {
                Console.log("There is no super administrator account in the system");
            }
        }
        Console.log("Time-consuming to start this time：{}", DateUtil.formatBetween(SystemClock.now() - time, BetweenFormatter.Level.MILLISECOND));
    }

    @Override
    public void applicationLoad() {
        DbConfig instance = DbConfig.getInstance();
        if (ArrayUtil.containsIgnoreCase(ARGS, "--rest:load_init_db")) {
            // 重新执行数据库初始化操作，一般用于手动修改数据库字段错误后，恢复默认的字段
            instance.clearExecuteSqlLog();
        }
        if (ArrayUtil.containsIgnoreCase(ARGS, "--recover:h2db")) {
            // 恢复数据库，一般用于非正常关闭程序导致数据库奔溃，执行恢复数据逻辑
            try {
                instance.recoverDb();
            } catch (Exception e) {
                e.printStackTrace();
                System.exit(-2);
            }
        }
        if (ArrayUtil.containsIgnoreCase(ARGS, "--backup-h2")) {
            // 备份数据库
            InitDb.addCallback(() -> {
                Console.log("Start backing up the database");
                BackupInfoService backupInfoService = SpringUtil.getBean(BackupInfoService.class);
                Future<BackupInfoModel> backupInfoModelFuture = backupInfoService.autoBackup();
                try {
                    BackupInfoModel backupInfoModel = backupInfoModelFuture.get();
                    Console.log("Complete the backup database, save the path as {}", backupInfoModel.getFilePath());
                    System.exit(0);
                } catch (Exception e) {
                    Console.error("Backup database failed：{}", e.getMessage());
                    e.printStackTrace();
                    System.exit(-2);
                }
            });
        }
        String importH2Sql = StringUtil.getArgsValue(ARGS, "import-h2-sql");
        if (StrUtil.isNotEmpty(importH2Sql)) {
            // 导入数据
            InitDb.addCallback(() -> {
                File file = FileUtil.file(importH2Sql);
                String sqlPath = FileUtil.getAbsolutePath(file);
                if (!FileUtil.isFile(file)) {
                    Console.error("sql file does not exist :{}", sqlPath);
                    System.exit(2);
                }
                BackupInfoService backupInfoService = SpringUtil.getBean(BackupInfoService.class);
                boolean flag = backupInfoService.restoreWithSql(sqlPath);
                if (!flag) {
                    Console.error("Failed to import according to sql,{}", sqlPath);
                    System.exit(2);
                }
                Console.log("Import successfully according to sql,{}", sqlPath);
            });
        }
    }
}

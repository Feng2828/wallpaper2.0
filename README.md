# 相册随机动态壁纸（Redmi K60E / HyperOS 2.0 适配）

一个**纯安卓动态壁纸程序**（`WallpaperService`），从你指定的相册文件夹随机换图。
没有电脑程序、没有网页、没有小程序，安装包就是 `RedmiAlbumWallpaper.apk`。

---

## 0. 先读：需求对照与两个必须知道的现实限制

| 需求 | 实现方式 |
| --- | --- |
| 选相册文件夹，一次性导入全部图片 | 系统文件夹选择器（SAF）整目录授权；选定后自动全量导入，含最多 4 层子文件夹 |
| 手动同步，不后台偷扫 | 全工程**只有**主界面「② 手动同步」按钮和首次选文件夹会调用扫描；没有任何定时器、WorkManager、后台轮询去扫相册 |
| 增/删同步 | 以 SAF 的 `documentId` 为准：新文件加入壁纸池、已删除的移除 |
| 随机切换 | 每次从壁纸池随机抽，尽量避开上一张，不按顺序 |
| 30 / 60 / 120 秒 | 主界面单选，默认 60 秒 |
| 双击切换 | ⚠️ HyperOS 下**无法监听桌面空白处双击**（系统桌面接管手势、第三方拿不到事件）。用**桌面悬浮球单击换一张**替代，这是本方案重点解决的痛点 |
| 悬浮球只在桌面显示、小球很小 | 前台服务 + 「使用情况访问」判断前台应用；小球 34dp，可拖动，进别的 App 自动隐藏 |
| 息屏→亮屏解锁自动换新图 | 壁纸引擎动态监听 `USER_PRESENT` / `SCREEN_ON`，解锁后随机换一张 |
| 简洁界面 | 只有：选文件夹、手动同步、切换间隔、悬浮球，外加必要的「设为壁纸」「权限与保活设置」两个入口 |
| 权限与保活 | 主动申请相册/通知权限；引导悬浮窗、使用情况访问、动态壁纸设置；前台服务 + 开机/更新自恢复 |
| 覆盖更新不卸载 | 固定签名 `signing/wallpaper.p12` + 每次构建 versionCode 递增 + 包名固定 `com.redmi.lwp` |

**限制一：小米相册里的「人物」「地点」「回忆」等智能分类不是真实文件夹**，它是相册数据库聚合出来的，系统文件选择器选不到。本 APP 只能选真实目录，例如 `DCIM/Camera`、`Pictures/风景`、`DCIM/人物`（你自己建的）。想要"人物"分类，请在文件管理器里把这些照片放进一个文件夹，再选它。

**限制二：Android 11+ 分区存储**，APP 不能靠读取权限直接遍历 DCIM，必须走系统文件夹授权（SAF）。所以"清空 APP 数据 / 恢复出厂 / 移动文件夹位置"后授权会失效，需要重新选一次文件夹（壁纸会提示）。

---

## 1. 打包方案：全程浏览器，不装 Android Studio

用 **GitHub Actions 云端编译**：把源码压缩包传上去，云端自动装 JDK 17 + Android SDK + Gradle 8.9，编译出签名好的 APK，并自动建一个 Release 供手机直接下载。**手机浏览器就能全程操作**（建议开「桌面版网站」）。

> 为什么不用 debug 签名？因为云端每次是全新机器，自动生成的 debug 密钥都不同，会导致"更新时必须先卸载"。本包里附了一个**固定密钥** `signing/wallpaper.p12`，所有版本共用它签名，所以可以直接覆盖安装。

---

## 2. 一步一步：打包出 APK

> ⚠️ 顺序别弄反：**先上传 `project.zip`，再创建 `build.yml`**，这样只会构建一次且一次成功。

**第 1 步｜登录 GitHub**
打开 `https://github.com` 注册并登录（手机浏览器建议切换到"桌面版网站"更好点按钮）。

**第 2 步｜新建仓库**
右上角 `+` → `New repository`：
- Repository name 填 `redmi-album-wallpaper`
- **务必选 `Private`（私有）**：压缩包里含签名私钥，公开仓库会泄露（虽然只是个人壁纸 APP，但别给别人拿去签同名应用）
- 其它默认，点 `Create repository`

**第 3 步｜打开写入权限（否则自动 Release 会失败）**
仓库 `Settings` → 左侧 `Actions` → `General` → 找到 `Workflow permissions` → 选 **`Read and write permissions`** → `Save`。

**第 4 步｜上传源码压缩包**
仓库首页 → `Add file` → `Upload files` → 选择本包里的 **`project.zip`** → 下方 `Commit changes`。
> 这一步只是上传文件，**不会开始编译**（仓库里还没有流水线文件）。

**第 5 步｜创建云端打包流水线**
仓库首页 → `Add file` → `Create new file`：
- 文件名输入框里填：**`.github/workflows/build.yml`**（输入 `/` 会自动建目录）
- 把本包里 `build.yml` 的内容整段复制粘贴进正文（全文见本文件末尾「附录 A」）
- 点 `Commit changes`

提交这一下就会**自动开始第一次构建**。

**第 6 步｜等构建完成**
仓库顶部 `Actions` → 点最新一条 `构建相册动态壁纸 APK` → 看日志跑完变绿，约 3～8 分钟（首次要下载 SDK，会久一点）。

**第 7 步｜下载 APK（两种方式，任选）**
- **方式 A（推荐）**：仓库右侧 `Releases` → 最新版 `相册动态壁纸 v1.0.x` → `Assets` 里点 **`RedmiAlbumWallpaper.apk`**，手机浏览器直接下载。
- **方式 B**：`Actions` → 点开这次运行 → 页面底部 `Artifacts` → 下载 `RedmiAlbumWallpaper-APK`（是个 zip，解压出 APK）。

**第 8 步｜以后更新怎么打包**
改完代码 → 重新生成/替换 `project.zip` → 仓库 `Add file` → `Upload files` → 把**同名** `project.zip` 拖进去覆盖提交 → Actions 自动再构建一次 → 下载新 APK → **手机上直接点开安装覆盖，不用卸载旧版**。
每次构建的 `versionCode` 会用 GitHub 运行号自动 +1，永远递增。

---

## 3. 安装到红米 K60E（HyperOS 2.0.25.0）

1. 把 `RedmiAlbumWallpaper.apk` 弄到手机：浏览器直接下载最简单，也可微信文件传输助手 / QQ / 数据线。
2. 点开 APK。若提示「禁止安装未知来源应用」→ 点「设置」→ 打开当前浏览器/文件管理器的 **「安装未知应用」** 允许 → 返回 → 安装。
3. 若 HyperOS 弹 **纯净模式 / 高风险应用拦截 / 安全验证**：选「仍要安装」「了解风险并继续」；反复拦截就到 `设置 → 应用设置 → 纯净模式` 临时关闭，装完再开回去。
4. 装完点「打开」。

---

## 4. 首次配置（按这个顺序做，一次到位）

1. **允许弹窗权限**：`照片和视频`、`通知`（通知是前台服务保活用的，别拒绝；之后可在系统里设为"静默"）。
2. **① 选择相册文件夹**：
   - 弹出的系统文件选择器里，点左上角 **三横线** → `内部存储设备` / `Redmi K60E`
   - 进到 `DCIM/Camera`，或 `Pictures/风景` 等你要的**真实文件夹**
   - 点右下/右上 **「使用此文件夹」** → 弹窗点 **「允许」**（这一步给的是长期读取授权，不用逐张勾选图片）
   - 选完会看到提示「正在一次性导入全部图片…」，状态栏出现 `壁纸池：N 张`
   - ⚠️ 不要选「内部存储根目录」，也不要选 `Android/data`、`Android/obb`，系统不允许。
3. **③ 切换间隔**：点 `30 秒` / `60 秒` / `120 秒`。
4. **设为壁纸**：点底部 `设为壁纸` →
   - 如果直接出现本壁纸预览页，点「设为壁纸」→ 选 **「桌面壁纸」**（或"同时设置"）
   - 如果 HyperOS 跳到了壁纸列表，请手动找到 **「相册随机壁纸」** 点进去设置（见第 7 节坑 5）
   - 回到 APP，状态栏应显示 `动态壁纸：已启用 ✓`
5. **④ 桌面悬浮球**：打开开关 →
   - 系统会跳到「显示在其他应用上层 / 悬浮窗」→ 打开允许 → 返回
   - 还可能跳到「使用情况访问」→ 在列表里找到 `相册动态壁纸` → 打开 `允许使用情况访问` → 返回
   - 回到桌面，右上角会出现一个很小的墨绿圆点。**点一下 = 随机换一张；按住可拖动挪位置。**
6. **权限与保活设置**：点这个按钮会打开系统应用详情页，接着做第 5 节的全部设置。

---

## 5. 日常使用规则（同步逻辑）

| 你做的动作 | 系统行为 |
| --- | --- |
| 第一次选中文件夹 | 自动扫描一次，把该文件夹（含最多 4 层子目录）里全部支持的图片导入壁纸池 |
| 往文件夹里加了照片 | 壁纸池**不会自动变**，除非你打开 APP 点「② 手动同步」 |
| 从文件夹里删了照片 | 同上，点同步后才会从壁纸池移除 |
| 不点同步 | APP 完全不扫描相册，壁纸池保持现状；壁纸轮播照常进行（用的是已缓存的池） |
| 点「② 手动同步」 | 只扫描这一次：新增的加入、删掉的移除、没变的不动 |
| 定时轮播 | 每 30/60/120 秒随机抽一张，尽量避免连续两张相同 |
| 息屏 → 亮屏 → 解锁 | 自动随机换一张（系统省电下可能延迟 1～3 秒） |
| 其它 App 在前台 | 悬浮球自动隐藏；回到桌面自动出现 |

> 「文件夹里没读到图片且旧池非空」时，APP 会**故意不清空**壁纸池，防止权限临时失效导致照片全没了。确认你真删光了，重新选一次文件夹即可。

---

## 6. HyperOS 保活：必须手动做的设置（不做容易停止切换）

打开 `设置 → 应用设置 → 应用管理 → 相册动态壁纸`，逐项确认：

- [ ] **省电策略 / 电池优化 → 「无限制」**（不要"智能限制后台运行"/"省电模式"）
- [ ] **自启动 → 开启**（也可在 `设置 → 应用设置 → 授权管理 → 自启动管理` 里确认）
- [ ] **通知 → 允许通知**。前台服务依赖常驻通知；可以设为「静默/不响铃」，但**不要关闭通知类别、不要手动划掉那条常驻通知**，HyperOS 划掉通知有时会连带杀服务
- [ ] **权限 → 照片和视频**：给「始终允许所有照片」最稳（实际读图靠的是文件夹授权）
- [ ] `设置 → 应用设置 → 授权管理 → 特殊权限 → 悬浮窗 / 显示在其他应用上层` → 允许
- [ ] `设置 → 应用设置 → 授权管理 → 特殊权限 → 使用情况访问` → 允许
- [ ] `设置 → 省电与电池` → 关闭「省电模式 / 超级省电」；「睡眠待机优化」「智能省电」把本 APP 加白名单
- [ ] **最近任务锁定**：打开一次 APP → 多任务界面 → 长按本 APP 卡片 → 点小锁图标锁定，避免「一键清理」杀掉
- [ ] 安全中心 / 手机管家的「内存加速、垃圾清理」把本 APP 加入保护名单
- [ ] 开发者选项里如果开着「暂停执行已缓存的应用」，**关掉**
- [ ] 别把 APP 放进「应用休眠 / 深度休眠 / 长期未使用自动回收权限」名单
- [ ] 系统大版本升级、换主题、恢复出厂后，**重新检查以上每一项**

---

## 7. HyperOS / 红米机型专属坑位清单

1. **桌面空白双击监听不到**：MIUI 14 上部分老软件靠私有接口/无障碍能拿到，HyperOS 收紧后桌面手势被系统接管，第三方普通应用拿不到空白双击。本 APP 用桌面悬浮球单击替代，是本次重点改动。
2. **智能相册选不了**：「人物 / 地点 / 回忆 / 智能分类」不是磁盘目录，文件选择器里不存在。要用真实文件夹。
3. **分区存储**：Android 11+ 光给 `READ_MEDIA_IMAGES` 也不能遍历 DCIM，必须 SAF 整目录授权；换路径、清数据、恢复出厂后要重选文件夹。
4. **禁选目录**：内部存储根目录、`Android/data`、`Android/obb` 系统不允许作为授权目录。
5. **壁纸选择器不配合**：HyperOS 的壁纸/主题 App 可能忽略 `EXTRA_LIVE_WALLPAPER_COMPONENT`，点了「设为壁纸」却跳到壁纸列表 → 手动找「相册随机壁纸」即可。部分主题切换、系统"一键美化"还会**把动态壁纸重置成静态壁纸**，发现不换图先回 APP 看状态栏是不是 `已启用 ✓`，不是就重新设一次。
6. **后台被杀**：最常见原因就是省电策略 + 自启动 + 一键清理 + 通知被关。症状：壁纸停在某一张不动、悬浮球消失。按第 6 节处理。
7. **Android 14+ 前台服务类型限制**：本 APP 声明 `specialUse` 类型 + `FOREGROUND_SERVICE_SPECIAL_USE`，比 `dataSync` 更适合长期运行（Android 15 下 `dataSync` 有约 6 小时超时会被系统停掉）。若把通知权限关了，前台服务可能被回收。
8. **亮屏换图有延迟**：Doze / 低电量 / 深度睡眠下系统广播会延后，解锁后 1～3 秒换图属正常；若完全不换，先检查壁纸是不是被系统重置成静态了。
9. **特殊权限被自动回收**：HyperOS 在系统升级、安全扫描、长时间未使用后可能自动关掉「悬浮窗」「使用情况访问」，APP 状态栏会提示"未授权"，去设置重新打开即可。
10. **没给「使用情况访问」就做不到自动隐藏**：未授权时 APP 采取降级策略——悬浮球保持可见（保证能换图），状态栏会明确写"未授权（悬浮球会一直显示）"。
11. **安装被拦**：纯净模式 / 安装未知应用 / 风险提示，按第 3 节处理。
12. **图片格式**：支持 JPEG / PNG / WebP / GIF / BMP / HEIC 等（系统 `ImageDecoder` 解码，自动处理 EXIF 旋转，长边限制 2560px 兼顾清晰与内存）；RAW / DNG / SVG / TIFF / ICO 会被跳过。个别 HEIC 解码失败时壁纸会显示「这张图片无法读取」，点一下悬浮球换下一张即可。
13. **超大文件夹**：最多导入 5000 张、最多向下 4 层子目录；几千张首次同步可能要十几秒，期间按钮显示「同步中…」是正常的。
14. **锁屏壁纸**：HyperOS 可能只把动态壁纸应用到桌面，锁屏仍是静态图；亮屏解锁换图作用于桌面壁纸引擎。
15. **覆盖更新的三条红线**：别换 `signing/wallpaper.p12`、别改 `applicationId`（`com.redmi.lwp`）、别用另一个仓库/另一把密钥构建。任一破坏都会报「应用未安装 / 签名冲突」，只能卸载重装（壁纸池等本地配置会清空）。
16. **清除数据 ≠ 重装**：清数据会丢掉文件夹授权和壁纸池，但不用卸载；重新选文件夹即可。

---

## 8. 覆盖更新为什么不用卸载

- 包名固定 `com.redmi.lwp`
- 每次构建都用同一把 `signing/wallpaper.p12` 签名（密钥随源码包一起提交，永不改变）
- `versionCode` = GitHub Actions 运行号，每次构建自动递增
- `android:allowBackup="true"`，SharedPreferences 里的文件夹授权、壁纸池、间隔、开关在覆盖安装后保留
- `BootReceiver` 监听 `MY_PACKAGE_REPLACED`，更新完成会自动把悬浮球前台服务拉回来

> 安全提醒：仓库设成 Private。若一定要公开，请把密钥改用 GitHub Secrets：把 `signing/wallpaper.p12` 转成 base64 存进 Secret `KEYSTORE_B64`，在 workflow 的「解压源码包」后加一步 `echo "${{ secrets.KEYSTORE_B64 }}" | base64 -d > signing/wallpaper.p12`，并从压缩包里删掉 p12 文件。

---

## 9. 备选：本地命令行打包（仍然不用 Android Studio，但要下载 SDK）

只在你想离线编译时用，需要 JDK 17 + Android command line tools + Gradle 8.9（约 1GB 下载），不推荐：

```bash
sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools"
export ANDROID_HOME=/path/to/android-sdk
gradle assembleRelease --no-daemon -PappVersionCode=2
# 产物：app/build/outputs/apk/release/app-release.apk
```

---

## 10. 项目结构与实现要点

```
RedmiAlbumWallpaper/
├── build.gradle / settings.gradle / gradle.properties
├── signing/wallpaper.p12              # 固定签名（务必保留、不要改）
├── .github/workflows/build.yml        # 云端打包流水线
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/redmi/lwp/
        │   ├── MainActivity.java          # 界面、权限、SAF 选文件夹、手动同步、设为壁纸
        │   ├── LiveWallpaperService.java  # 壁纸引擎：随机轮播 / 定时 / 亮屏解锁换图
        │   ├── PhotoScanner.java          # SAF 遍历 + docId 增量对比（唯一扫描逻辑）
        │   ├── BitmapLoader.java          # ImageDecoder 解码 + 居中裁剪铺满
        │   ├── FloatingBallService.java   # 前台服务 + 悬浮球 + 前台应用判断
        │   ├── BootReceiver.java          # 开机 / 覆盖更新后恢复服务
        │   └── Prefs.java                 # SharedPreferences + 壁纸池 JSON
        └── res/                          # 布局、图标、壁纸描述、字符串、主题
```

- **不后台偷扫的证据**：全工程只有 `MainActivity.doSync()` 会调用 `PhotoScanner.sync()`，它被「② 手动同步」按钮和首次选文件夹回调调用；没有任何 `AlarmManager` / `WorkManager` / `JobScheduler` / `ContentObserver` / 后台定时扫描。
- **画质**：`ImageDecoder` 解码，长边上限 2560px，自动 EXIF 旋转，按壁纸 surface 实际尺寸居中裁剪铺满，不做低分辨率压缩。
- **保活**：动态壁纸服务由系统 `WallpaperManagerService` 绑定；另有 `specialUse` 前台服务 + `START_STICKY` + 开机/更新自恢复，配合第 6 节手动白名单设置。
- **纯 Android framework 实现，零第三方依赖**，云端构建不会因依赖下载失败而挂掉。

---

## 附录 A：`.github/workflows/build.yml` 全文

（与随包 `build.yml` 文件内容一致，直接复制粘贴即可）

```yaml
name: 构建相册动态壁纸 APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: 检出仓库
        uses: actions/checkout@v4

      - name: 解压源码包
        id: prep
        shell: bash
        run: |
          if [ ! -f project.zip ]; then
            echo "::notice::仓库里还没有 project.zip，本次跳过构建（先上传 project.zip，再创建本 workflow 即可）"
            echo "ready=false" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          unzip -o -q project.zip -d .
          echo "ready=true" >> "$GITHUB_OUTPUT"

      - name: 安装 JDK 17
        if: steps.prep.outputs.ready == 'true'
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: 安装 Android SDK
        if: steps.prep.outputs.ready == 'true'
        uses: android-actions/setup-android@v3

      - name: 安装 platform / build-tools
        if: steps.prep.outputs.ready == 'true'
        shell: bash
        run: |
          yes | sdkmanager --licenses > /dev/null 2>&1 || true
          sdkmanager --install "platforms;android-35" "build-tools;35.0.0" "platform-tools" > /dev/null

      - name: 安装 Gradle 8.9
        if: steps.prep.outputs.ready == 'true'
        shell: bash
        run: |
          curl -fsSL https://services.gradle.org/distributions/gradle-8.9-bin.zip -o "$RUNNER_TEMP/gradle.zip"
          unzip -q "$RUNNER_TEMP/gradle.zip" -d "$RUNNER_TEMP"
          echo "$RUNNER_TEMP/gradle-8.9/bin" >> "$GITHUB_PATH"

      - name: 校验固定签名（保证后续可直接覆盖安装）
        if: steps.prep.outputs.ready == 'true'
        shell: bash
        run: |
          keytool -list -keystore signing/wallpaper.p12 -storetype PKCS12 -storepass albumwp2026 \
            | grep -i "wallpaperkey"

      - name: 编译 Release APK
        if: steps.prep.outputs.ready == 'true'
        shell: bash
        run: |
          gradle assembleRelease --no-daemon --stacktrace -PappVersionCode=${{ github.run_number }}
          cp app/build/outputs/apk/release/app-release.apk RedmiAlbumWallpaper.apk
          ls -lh RedmiAlbumWallpaper.apk

      - name: 上传 APK 构建产物
        if: steps.prep.outputs.ready == 'true'
        uses: actions/upload-artifact@v4
        with:
          name: RedmiAlbumWallpaper-APK
          path: RedmiAlbumWallpaper.apk
          if-no-files-found: error

      - name: 生成 Release（手机浏览器可直接下载 APK）
        if: steps.prep.outputs.ready == 'true'
        continue-on-error: true
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1.0.${{ github.run_number }}
          name: 相册动态壁纸 v1.0.${{ github.run_number }}
          body: |
            同一签名、versionCode 递增，下载后直接打开即可覆盖更新，无需卸载旧版。
            红米 K60E / HyperOS 首次安装请按 README 完成权限与保活设置。
          files: RedmiAlbumWallpaper.apk
```

; 布莱克启动器 Windows 安装向导（Inno Setup 6）
; 根目录下自动创建 BlackLauncher + MinecraftGame（§8）
; 编译: ISCC.exe installer\BlackLauncher.iss
; 或: gradlew.bat buildInstaller

#define MyAppName "布莱克启动器"
#define MyAppNameEn "Black Launcher"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Liveworld Games"
#define MyAppExeName "BlackLauncher.bat"
#define MyAppId "{{A8F3C2E1-9B47-4D6A-8E21-BLACKLAUNCHER01}"

#ifndef DistDir
  #define DistDir "..\build\dist\payload"
#endif
#ifndef OutDir
  #define OutDir "..\build\dist"
#endif

[Setup]
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName=C:\BlackLauncher
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=no
LicenseFile=license.txt
OutputDir={#OutDir}
OutputBaseFilename=BlackLauncher-Setup-{#MyAppVersion}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName={#MyAppName}
UninstallDisplayIcon={app}\BlackLauncher\{#MyAppExeName}
DirExistsWarning=no
UsePreviousAppDir=yes
WizardSizePercent=100
SetupLogging=yes
; 关闭后仍可通过日志查看；安装失败会弹窗
CloseApplications=force
RestartApplications=no
InfoBeforeFile=
; 备注：自制程序可能被杀软误报，无恶意行为

[Languages]
; 本机 Inno 可能无中文语言包：用 Default + 下方中文 Messages 覆盖
Name: "zh"; MessagesFile: "compiler:Default.isl"

[Messages]
zh.BeveledLabel=布莱克启动器
zh.WelcomeLabel1=欢迎使用布莱克启动器安装向导
zh.WelcomeLabel2=本启动器用于启动 Minecraft Java 版。%n%n点击「下一步」继续，或点击「取消」退出安装。%n%n提示：本程序为自制软件，部分杀毒软件可能误报，无恶意行为。
zh.WizardLicense=用户许可协议
zh.LicenseLabel3=请阅读下列协议。勾选同意后方可继续。
zh.LicenseAccepted=我已经阅读并同意《布莱克启动器用户许可协议》
zh.LicenseNotAccepted=我不同意该协议
zh.WizardSelectDir=选择安装位置
zh.SelectDirDesc=选择布莱克启动器的安装根目录
zh.SelectDirLabel3=安装后将在该根目录下自动创建 BlackLauncher（程序）与 MinecraftGame（游戏）两个文件夹。
zh.WizardSelectTasks=附加任务
zh.SelectTasksDesc=请选择需要创建的附加任务，然后点击「下一步」。
zh.WizardReady=准备安装
zh.ReadyLabel1=即将开始安装布莱克启动器。
zh.ReadyLabel2a=确认下列信息无误后，点击「安装」继续。
zh.WizardInstalling=正在安装
zh.StatusCreateDirs=正在创建目录结构…
zh.StatusExtractFiles=正在复制启动器程序文件…
zh.StatusCreateIcons=正在创建快捷方式…
zh.FinishedLabel=布莱克启动器安装完成！
zh.FinishedRestartLabel=可以开始使用布莱克启动器。
zh.ClickFinish=点击「完成」退出安装向导。
zh.ButtonNext=下一步(&N)
zh.ButtonBack=上一步(&B)
zh.ButtonCancel=取消
zh.ButtonInstall=安装(&I)
zh.ButtonFinish=完成(&F)
zh.ExitSetupTitle=退出安装
zh.ExitSetupMessage=安装尚未完成。确定退出吗？

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; GroupDescription: "附加任务:"; Flags: checkedonce
Name: "startmenu"; Description: "创建开始菜单快捷方式"; GroupDescription: "附加任务:"; Flags: checkedonce

[Files]
; 启动器程序 → {root}\BlackLauncher\
Source: "{#DistDir}\*"; DestDir: "{app}\BlackLauncher"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
; 游戏目录骨架（不预装版本）
Name: "{app}\MinecraftGame"
Name: "{app}\MinecraftGame\versions"
Name: "{app}\MinecraftGame\libraries"
Name: "{app}\MinecraftGame\assets"
Name: "{app}\MinecraftGame\natives"
Name: "{app}\MinecraftGame\mods"
Name: "{app}\MinecraftGame\saves"
Name: "{app}\MinecraftGame\resourcepacks"
Name: "{app}\MinecraftGame\shaderpacks"
Name: "{app}\MinecraftGame\config"
Name: "{app}\MinecraftGame\instances"

[Icons]
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\BlackLauncher\{#MyAppExeName}"; WorkingDir: "{app}\BlackLauncher"; Tasks: desktopicon; Comment: "{#MyAppNameEn}"
Name: "{group}\{#MyAppName}"; Filename: "{app}\BlackLauncher\{#MyAppExeName}"; WorkingDir: "{app}\BlackLauncher"; Tasks: startmenu
Name: "{group}\卸载 {#MyAppName}"; Filename: "{uninstallexe}"; Tasks: startmenu

[Registry]
Root: HKCU; Subkey: "Software\BlackLauncher"; ValueType: string; ValueName: "InstallRoot"; ValueData: "{app}"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\BlackLauncher"; ValueType: string; ValueName: "LauncherDir"; ValueData: "{app}\BlackLauncher"
Root: HKCU; Subkey: "Software\BlackLauncher"; ValueType: string; ValueName: "GameDir"; ValueData: "{app}\MinecraftGame"
Root: HKCU; Subkey: "Software\BlackLauncher"; ValueType: string; ValueName: "Version"; ValueData: "{#MyAppVersion}"

[Run]
Filename: "{app}\BlackLauncher\{#MyAppExeName}"; Description: "立即运行布莱克启动器"; Flags: nowait postinstall skipifsilent shellexec

[UninstallDelete]
; 仅清理启动器目录内残留；MinecraftGame 由代码按用户选择处理
Type: filesandordirs; Name: "{app}\BlackLauncher"

[Code]
var
  PathHintLabel: TNewStaticText;
  PathErrorLabel: TNewStaticText;

function IsAsciiPath(const S: string): Boolean;
var
  I: Integer;
  C: Char;
begin
  Result := True;
  for I := 1 to Length(S) do
  begin
    C := S[I];
    if (Ord(C) < 32) or (Ord(C) > 126) then
    begin
      Result := False;
      Exit;
    end;
  end;
end;

function IsForbiddenPath(const S: string): Boolean;
var
  U: string;
begin
  U := UpperCase(S);
  Result :=
    (Pos('\WINDOWS', U) > 0) or
    (Pos('\WINNT', U) > 0) or
    (Pos('\PROGRAM FILES', U) > 0) or
    (Pos('\PROGRAM FILES (X86)', U) > 0) or
    (Pos('\PROGRAMDATA', U) > 0) or
    (CompareText(S, 'C:\') = 0) or
    (CompareText(S, 'C:') = 0);
end;

function HasEnoughSpace(const Dir: string): Boolean;
var
  Free, Total: Cardinal;
  PathRoot: string;
begin
  Result := True;
  PathRoot := ExtractFileDrive(Dir) + '\';
  if GetSpaceOnDisk(PathRoot, True, Free, Total) then
  begin
    { Free is in KB when ConvertToBytes=False? Actually with True it's bytes as Cardinal - use GetSpaceOnDisk64 if available }
    Result := Free > 2 * 1024 * 1024; { approx when Free is KB in older API }
  end;
end;

function ValidateInstallRoot(const Dir: string; out Err: string): Boolean;
begin
  Result := False;
  Err := '';
  if Dir = '' then
  begin
    Err := '请选择安装根目录';
    Exit;
  end;
  if not IsAsciiPath(Dir) then
  begin
    Err := '路径禁止包含中文或特殊符号，请使用纯英文/数字路径（例如 C:\BlackLauncher）';
    Exit;
  end;
  if IsForbiddenPath(Dir) then
  begin
    Err := '请勿安装到系统关键目录（Windows / Program Files 等）';
    Exit;
  end;
  Result := True;
end;

procedure InitializeWizard;
begin
  WizardForm.LicenseAcceptedRadio.Caption := '我已经阅读并同意《布莱克启动器用户许可协议》';
  WizardForm.LicenseNotAcceptedRadio.Visible := False;
  WizardForm.LicenseAcceptedRadio.Checked := False;

  PathHintLabel := TNewStaticText.Create(WizardForm);
  PathHintLabel.Parent := WizardForm.SelectDirPage;
  PathHintLabel.Left := WizardForm.DirEdit.Left;
  PathHintLabel.Top := WizardForm.DirEdit.Top + WizardForm.DirEdit.Height + ScaleY(12);
  PathHintLabel.Width := WizardForm.DirEdit.Width;
  PathHintLabel.Height := ScaleY(70);
  PathHintLabel.WordWrap := True;
  PathHintLabel.Caption :=
    '安装后将在所选目录自动创建两个文件夹：' + #13#10 +
    'BlackLauncher：启动器程序目录' + #13#10 +
    'MinecraftGame：Minecraft 游戏目录（存档、模组、版本都保存在这里）' + #13#10 +
    '重装启动器不会删除 MinecraftGame 内的游戏数据';
  PathHintLabel.Font.Color := clGray;

  PathErrorLabel := TNewStaticText.Create(WizardForm);
  PathErrorLabel.Parent := WizardForm.SelectDirPage;
  PathErrorLabel.Left := PathHintLabel.Left;
  PathErrorLabel.Top := PathHintLabel.Top + PathHintLabel.Height + ScaleY(6);
  PathErrorLabel.Width := PathHintLabel.Width;
  PathErrorLabel.Height := ScaleY(36);
  PathErrorLabel.WordWrap := True;
  PathErrorLabel.Font.Color := clRed;
  PathErrorLabel.Caption := '';
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  Err: string;
  Root: string;
begin
  Result := True;
  if CurPageID = wpLicense then
  begin
    if not WizardForm.LicenseAcceptedRadio.Checked then
    begin
      MsgBox('请先勾选同意《布莱克启动器用户许可协议》。', mbError, MB_OK);
      Result := False;
    end;
  end
  else if CurPageID = wpSelectDir then
  begin
    Root := WizardDirValue;
    if not ValidateInstallRoot(Root, Err) then
    begin
      PathErrorLabel.Caption := Err;
      MsgBox(Err, mbError, MB_OK);
      Result := False;
      Exit;
    end;
    PathErrorLabel.Caption := '';
    if DirExists(Root + '\BlackLauncher') or DirExists(Root + '\MinecraftGame') then
    begin
      if MsgBox('目标目录已存在 BlackLauncher 或 MinecraftGame。'#13#10 +
                '继续将更新启动器程序文件，并保留 MinecraftGame 中的存档与模组。'#13#10 +
                '是否继续？', mbConfirmation, MB_YESNO) <> IDYES then
        Result := False;
    end;
  end;
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if CurPageID = wpSelectDir then
  begin
    WizardForm.SelectDirLabel.Caption := '选择布莱克启动器的安装根目录（推荐 C:\BlackLauncher）';
  end
  else if CurPageID = wpReady then
  begin
    WizardForm.ReadyMemo.Lines.Clear;
    WizardForm.ReadyMemo.Lines.Add('安装位置：' + WizardDirValue);
    WizardForm.ReadyMemo.Lines.Add('启动器目录：' + WizardDirValue + '\BlackLauncher');
    WizardForm.ReadyMemo.Lines.Add('游戏目录：' + WizardDirValue + '\MinecraftGame');
    if WizardIsTaskSelected('desktopicon') then
      WizardForm.ReadyMemo.Lines.Add('桌面快捷方式：是')
    else
      WizardForm.ReadyMemo.Lines.Add('桌面快捷方式：否');
    if WizardIsTaskSelected('startmenu') then
      WizardForm.ReadyMemo.Lines.Add('开始菜单快捷方式：是')
    else
      WizardForm.ReadyMemo.Lines.Add('开始菜单快捷方式：否');
    WizardForm.ReadyMemo.Lines.Add('');
    WizardForm.ReadyMemo.Lines.Add('确认无误后点击【安装】开始安装');
    WizardForm.ReadyMemo.Lines.Add('提示：请预留至少 2GB 磁盘空间供后续下载游戏版本');
  end;
end;

function JsonEscapePath(const S: string): string;
begin
  Result := S;
  StringChangeEx(Result, '\', '/', True); { JSON-friendly forward slashes }
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  Json, LogPath, Root: string;
begin
  if CurStep = ssPostInstall then
  begin
    Root := ExpandConstant('{app}');
    Json :=
      '{' + #13#10 +
      '  "schema": 1,' + #13#10 +
      '  "root": "' + JsonEscapePath(Root) + '",' + #13#10 +
      '  "launcherDir": "' + JsonEscapePath(Root + '\BlackLauncher') + '",' + #13#10 +
      '  "gameDir": "' + JsonEscapePath(Root + '\MinecraftGame') + '"' + #13#10 +
      '}' + #13#10;
    ForceDirectories(ExpandConstant('{app}\BlackLauncher'));
    SaveStringToFile(ExpandConstant('{app}\BlackLauncher\install.json'), Json, False);

    LogPath := ExpandConstant('{app}\BlackLauncher\install.log');
    SaveStringToFile(LogPath,
      'Black Launcher installed' + #13#10 +
      'Root=' + Root + #13#10 +
      'Time=' + GetDateTimeString('yyyy-mm-dd hh:nn:ss', '-', ':') + #13#10, False);
  end;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  GameDir: string;
begin
  if CurUninstallStep = usUninstall then
  begin
    GameDir := ExpandConstant('{app}\MinecraftGame');
    if DirExists(GameDir) then
    begin
      if MsgBox('即将卸载布莱克启动器程序。'#13#10#13#10 +
                '默认不会删除 MinecraftGame 游戏文件夹，你的存档、模组会保留。'#13#10#13#10 +
                '是否同时删除全部游戏数据（MinecraftGame 文件夹）？'#13#10 +
                '选【是】将清空存档与模组；选【否】仅删除启动器。',
                mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
      begin
        DelTree(GameDir, True, True, True);
      end;
    end;
  end;
end;

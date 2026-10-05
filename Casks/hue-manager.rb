cask "hue-manager" do
  version "2026.10.05-c33e37c"
  sha256 "a2602a38e3b4986b92064c7de434a46963401ae06eaaea8056de5b4eae4c52c5"

  url "https://github.com/CommanderTvis/hue-manager/releases/download/nightly/hue-manager.dmg"
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: :ventura

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end

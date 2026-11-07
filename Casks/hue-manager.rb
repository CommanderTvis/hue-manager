cask "hue-manager" do
  version "2026.11.07-43579c2"
  sha256 "564423af1d9cbd6764ef9aea48a77548ed428887dfc3aa116c2f5d99d6f7c7fa"

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

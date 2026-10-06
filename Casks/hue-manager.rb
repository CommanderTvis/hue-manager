cask "hue-manager" do
  version "2026.10.06-57c7af1"
  sha256 "484e7fa552a3db0c04efd1d71f88a554116517e27102b49a28b1c1afd9f90c13"

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

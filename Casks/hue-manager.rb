cask "hue-manager" do
  version "2026.10.05-0e17e23"
  sha256 "4ea67cfc890b30cbff25e49b1ba8f675f439c18a201e371e5a5d1e39fec3511d"

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

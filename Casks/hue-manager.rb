cask "hue-manager" do
  version "2026.10.06-6d0c1db"
  sha256 "6539218e2f0a00099babbf5fcc80aa3c0461c0d432fcacea89cf37a3ee0877ba"

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

cask "hue-manager" do
  version "2026.05.26-2adbdff"
  sha256 "ec5cc649c8603653083d66b1ab518e8d9927718c452cfcd0ee997a8dce72ee85"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/430230493",
      header: [
        "Authorization: token #{ENV.fetch("HOMEBREW_GITHUB_API_TOKEN", "")}",
        "Accept: application/octet-stream",
      ]
  name "Hue Manager"
  desc "Philips Hue lamp management desktop application"
  homepage "https://github.com/CommanderTvis/hue-manager"

  depends_on macos: ">= :ventura"

  app "Hue Manager.app"

  zap trash: [
    "~/Library/Preferences/io.github.commandertvis.huemanager.plist",
    "~/Library/Application Support/io.github.commandertvis.huemanager",
  ]
end

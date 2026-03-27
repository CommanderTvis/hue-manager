cask "hue-manager" do
  version "2026.03.27-436b416"
  sha256 "58459fd292cee4613eeb85f5bcf38cb767530f920f11c0e1162111eee72913be"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/383165432",
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

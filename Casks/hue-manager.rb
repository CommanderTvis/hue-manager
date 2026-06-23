cask "hue-manager" do
  version "2026.06.23-70606b6"
  sha256 "524756f6f002e0ac110f2301cd2fa65bef47f246c735f601ee73354806704c09"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455751121",
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

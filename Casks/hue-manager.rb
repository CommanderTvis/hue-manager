cask "hue-manager" do
  version "2026.03.27-436b416"
  sha256 "83f1b7ed5c622138d277d1ed6707251687038d0e30508dad26628921df5dd007"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/383163759",
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

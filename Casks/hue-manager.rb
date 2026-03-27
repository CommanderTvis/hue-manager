cask "hue-manager" do
  version "2026.03.27-c995eb0"
  sha256 "401f17e33afeac8734f99c81ac0171c606be3247dc07c4de01638c6b65effd46"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/383162443",
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

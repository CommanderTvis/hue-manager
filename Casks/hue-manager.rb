cask "hue-manager" do
  version "2026.06.26-a3f5a36"
  sha256 "e2ac80e5594d420ea00dffef5900c435ecddd7b36a87ab6211ceab9ce5fb140d"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/458858336",
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

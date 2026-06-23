cask "hue-manager" do
  version "2026.06.23-763eb08"
  sha256 "fec6a94509d3458121e72ff15f31d6650d270d3029d4073aa3b96127b37463a7"

  url "https://api.github.com/repos/CommanderTvis/hue-manager/releases/assets/455817211",
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

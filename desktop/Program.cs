using System;
using System.Windows.Forms;

namespace MHPlayer.Windows;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new PlayerForm(args));
    }
}